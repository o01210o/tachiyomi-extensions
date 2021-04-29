package eu.kanade.tachiyomi.extension.pt.hipercool

import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.int
import com.github.salomonbrys.kotson.jsonObject
import com.github.salomonbrys.kotson.obj
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.annotations.Nsfw
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

@Nsfw
class Hipercool : HttpSource() {

    // Hardcode the id because the language wasn't specific.
    override val id: Long = 5898568703656160

    override val name = "HipercooL"

    override val baseUrl = "https://hiper.cool"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(RateLimitInterceptor(1, 1, TimeUnit.SECONDS))
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", baseUrl)
        .add("X-Requested-With", "XMLHttpRequest")

    private fun genericMangaListParse(response: Response): MangasPage {
        val result = response.asJson().array

        if (result.size() == 0)
            return MangasPage(emptyList(), false)

        val mangaList = result
            .map { genericMangaFromObject(it.obj) }
            .distinctBy { it.title }

        val hasNextPage = result.size() == DEFAULT_COUNT

        return MangasPage(mangaList, hasNextPage)
    }

    private fun genericMangaFromObject(obj: JsonObject): SManga {
        val book = obj["_book"].obj
        val bookSlug = book["slug"].string
        val bookRevision = book["revision"]?.int ?: 1

        return SManga.create().apply {
            title = book["title"].string
            thumbnail_url = bookSlug.toThumbnailUrl(bookRevision)
            url = "/books/$bookSlug"
        }
    }

    // The source does not have popular mangas, so use latest instead.
    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = genericMangaListParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val start = (page - 1) * DEFAULT_COUNT
        return GET("$baseUrl/api/books/chapters?start=$start&count=$DEFAULT_COUNT", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = genericMangaListParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

        // Create json body.
        val json = jsonObject(
            "start" to (page - 1) * DEFAULT_COUNT,
            "count" to DEFAULT_COUNT,
            "text" to query,
            "type" to "text"
        )

        val body = json.toString().toRequestBody(mediaType)

        return POST("$baseUrl/api/books/chapters/search", headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage = genericMangaListParse(response)

    // Workaround to allow "Open in browser" use the real URL.
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsApiRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    private fun mangaDetailsApiRequest(manga: SManga): Request {
        val slug = manga.url.substringAfterLast("/")

        return GET("$baseUrl/api/books/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.asJson().obj

        val artists = result["tags"].array
            .filter { it["label"].string == "Artista" }
            .flatMap { it["values"].array }
            .joinToString("; ") { it["label"].string }

        val authors = result["tags"].array
            .filter { it["label"].string == "Autor" }
            .flatMap { it["values"].array }
            .joinToString("; ") { it["label"].string }

        val tags = result["tags"].array
            .filter { it["label"].string == "Tags" }
            .flatMap { it["values"].array }
            .joinToString(", ") { it["label"].string }

        return SManga.create().apply {
            title = result["title"].string
            thumbnail_url = result["slug"].string.toThumbnailUrl(result["revision"].int)
            description = result["synopsis"]?.string ?: ""
            artist = artists
            author = authors
            genre = tags
        }
    }

    // Chapters are available in the same url of the manga details.
    override fun chapterListRequest(manga: SManga): Request = mangaDetailsApiRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.asJson().obj

        if (!result["chapters"]!!.isJsonArray)
            return emptyList()

        return result["chapters"].array
            .map { chapterListItemParse(result, it.obj) }
            .reversed()
    }

    private fun chapterListItemParse(book: JsonObject, obj: JsonObject): SChapter = SChapter.create().apply {
        name = obj["title"].string
        chapter_number = obj["title"].string.toFloatOrNull() ?: -1f
        // The property is written wrong.
        date_upload = DATE_FORMATTER.tryParseTime(obj["publishied_at"].string)

        val fullUrl = "$baseUrl/books".toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment(book["slug"].string)
            .addPathSegment(obj["slug"].string)
            .addQueryParameter("images", obj["images"].int.toString())
            .addQueryParameter("revision", book["revision"].int.toString())
            .toString()

        setUrlWithoutDomain(fullUrl)
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val chapterUrl = (baseUrl + chapter.url).toHttpUrlOrNull()!!

        val bookSlug = chapterUrl.pathSegments[1]
        val chapterSlug = chapterUrl.pathSegments[2]
        val images = chapterUrl.queryParameter("images")!!.toInt()
        val revision = chapterUrl.queryParameter("revision")!!.toInt()

        val pages = arrayListOf<Page>()

        // Create the pages.
        for (i in 1..images) {
            val imageUrl = "$STATIC_URL/books".toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment(bookSlug)
                .addPathSegment(chapterSlug)
                .addPathSegment("$bookSlug-chapter-$chapterSlug-page-$i.jpg")
                .addQueryParameter("revision", revision.toString())
                .toString()

            pages += Page(i - 1, chapter.url, imageUrl)
        }

        return Observable.just(pages)
    }

    override fun pageListParse(response: Response): List<Page> = throw Exception("This method should not be called!")

    override fun fetchImageUrl(page: Page): Observable<String> {
        return Observable.just(page.imageUrl!!)
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", baseUrl + page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    private fun SimpleDateFormat.tryParseTime(date: String): Long {
        return try {
            parse(date.substringBefore("T"))?.time ?: 0L
        } catch (e: ParseException) {
            0L
        }
    }

    private fun String.toThumbnailUrl(revision: Int): String =
        "$STATIC_URL/books".toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment(this)
            .addPathSegment("$this-cover.jpg")
            .addQueryParameter("revision", revision.toString())
            .toString()

    private fun Response.asJson(): JsonElement = JsonParser.parseString(body!!.string())

    companion object {
        private const val STATIC_URL = "https://static.hiper.cool"

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.128 Safari/537.36"

        private const val DEFAULT_COUNT = 40

        private val DATE_FORMATTER by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }
    }
}
