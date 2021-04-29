package eu.kanade.tachiyomi.extension.en.catmanga

import android.app.Application
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CatManga : HttpSource() {

    override val name = "CatManga"
    override val baseUrl = "https://catmanga.org"
    override val supportsLatest = true
    override val lang = "en"

    override fun popularMangaRequest(page: Int) = GET(baseUrl)

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = popularMangaRequest(page)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return client.newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map { response ->
                val mangas = if (query.startsWith(SERIES_ID_SEARCH_PREFIX)) {
                    getFilteredSeriesList(
                        response.asJsoup().getDataJsonObject(),
                        idFilter = query.removePrefix(SERIES_ID_SEARCH_PREFIX)
                    )
                } else {
                    getFilteredSeriesList(
                        response.asJsoup().getDataJsonObject(),
                        titleFilter = query
                    )
                }
                MangasPage(mangas, false)
            }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val mangas = getFilteredSeriesList(response.asJsoup().getDataJsonObject())
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val latests = response.asJsoup().getDataJsonObject()
            .getJSONObject("props")
            .getJSONObject("pageProps")
            .getJSONArray("latests")
        val mangas = (0 until latests.length()).map { i ->
            val manga = latests.getJSONArray(i).getJSONObject(0)
            SManga.create().apply {
                url = "/series/${manga.getString("series_id")}"
                title = manga.getString("title")
                thumbnail_url = manga.getJSONObject("cover_art").getString("source")
            }
        }
        return MangasPage(mangas, false)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return SManga.create().apply {
            val series = response.asJsoup().getDataJsonObject()
                .getJSONObject("props")
                .getJSONObject("pageProps")
                .getJSONObject("series")
            title = series.getString("title")
            author = series.getJSONArray("authors").joinToString(", ")
            description = series.getString("description")
            genre = series.getJSONArray("genres").joinToString(", ")
            status = when (series.getString("status")) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = series.getJSONObject("cover_art").getString("source")
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val jsonObject = response.asJsoup().getDataJsonObject()

        val querySeries = jsonObject.getJSONObject("query").getString("series")
        val seriesUrl = jsonObject.getString("page").replace("[series]", querySeries)
        val seriesPrefs = Injekt.get<Application>().getSharedPreferences("source_${id}_time_found:$querySeries", 0)
        val seriesPrefsEditor = seriesPrefs.edit()

        val series = jsonObject.getJSONObject("props").getJSONObject("pageProps").getJSONObject("series")
        val chapters = series.getJSONArray("chapters")
        val list = (0 until chapters.length()).reversed().map { i ->
            val chapter = chapters.getJSONObject(i)
            val title = chapter.optString("title")
            val groups = chapter.getJSONArray("groups").joinToString()
            val number = chapter.getString("number")
            val displayNumber = chapter.optString("display_number", number)
            SChapter.create().apply {
                url = "$seriesUrl/$number"
                chapter_number = number.toFloat()
                name = "Chapter $displayNumber" + if (title.isNotBlank()) " - $title" else ""
                scanlator = groups

                // Save current time when a chapter is found for the first time, and reuse it on future checks to
                // prevent manga entry without any new chapter bumped to the top of "Latest chapter" list
                // when the library is updated.
                val currentTimeMillis = System.currentTimeMillis()
                if (!seriesPrefs.contains(number)) {
                    seriesPrefsEditor.putLong(number, currentTimeMillis)
                }
                date_upload = seriesPrefs.getLong(number, currentTimeMillis)
            }
        }
        seriesPrefsEditor.apply()
        return list
    }

    override fun pageListParse(response: Response): List<Page> {
        val pages = response.asJsoup().getDataJsonObject()
            .getJSONObject("props")
            .getJSONObject("pageProps")
            .getJSONArray("pages")
        return (0 until pages.length()).map { i -> Page(i, "", pages.getString(i)) }
    }

    /**
     * Returns json object of site data
     */
    private fun Document.getDataJsonObject(): JSONObject {
        return JSONObject(getElementById("__NEXT_DATA__").html())
    }

    /**
     * @return filtered series from home page
     * @param data json data from [getDataJsonObject]
     * @param titleFilter will be used to check against title and alt_titles, null to disable filter
     * @param idFilter will be used to check against id, null to disable filter, only used when [titleFilter] is unset
     */
    private fun getFilteredSeriesList(
        data: JSONObject,
        titleFilter: String? = null,
        idFilter: String? = null
    ): List<SManga> {
        val series = data.getJSONObject("props").getJSONObject("pageProps").getJSONArray("series")
        val mangas = mutableListOf<SManga>()
        for (i in 0 until series.length()) {
            val manga = series.getJSONObject(i)
            val mangaId = manga.getString("series_id")
            val mangaTitle = manga.getString("title")
            val mangaAltTitles = manga.getJSONArray("alt_titles")

            // Filtering
            if (titleFilter != null) {
                if (!(mangaTitle.contains(titleFilter, true) || mangaAltTitles.contains(titleFilter))) {
                    continue
                }
            } else if (idFilter != null) {
                if (!mangaId.contains(idFilter, true)) {
                    continue
                }
            }

            mangas += SManga.create().apply {
                url = "/series/$mangaId"
                title = mangaTitle
                thumbnail_url = manga.getJSONObject("cover_art").getString("source")
            }
        }
        return mangas.toList()
    }

    private fun JSONArray.joinToString(separator: String = ", "): String {
        val stringBuilder = StringBuilder()
        for (i in 0 until length()) {
            if (i > 0) stringBuilder.append(separator)
            val item = getString(i)
            stringBuilder.append(item)
        }
        return stringBuilder.toString()
    }

    /**
     * For string objects
     */
    private operator fun JSONArray.contains(other: CharSequence): Boolean {
        for (i in 0 until length()) {
            if (optString(i, "").contains(other, true)) {
                return true
            }
        }
        return false
    }

    override fun searchMangaParse(response: Response): MangasPage {
        throw UnsupportedOperationException("Not used.")
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used.")
    }

    companion object {
        const val SERIES_ID_SEARCH_PREFIX = "series_id:"
    }
}
