package eu.kanade.tachiyomi.multisrc.webtoons

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceData.MultiLang
import generator.ThemeSourceGenerator

class WebtoonsGenerator : ThemeSourceGenerator {

    override val themePkg = "webtoons"

    override val themeClass = "Webtoons"

    override val baseVersionCode: Int = 1

    override val sources = listOf(
        MultiLang("Webtoons.com", "https://www.webtoons.com", listOf("en", "fr", "es", "id", "th", "zh"), className = "WebtoonsFactory", pkgName = "webtoons", overrideVersionCode = 27),
        SingleLang("Dongman Manhua", "https://www.dongmanmanhua.cn", "zh")
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            WebtoonsGenerator().createAll()
        }
    }
}
