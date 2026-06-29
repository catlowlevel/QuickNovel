package com.lagradost.quicknovel.providers

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import com.lagradost.quicknovel.util.AppUtils.parseJson
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt


class  WtrLabProvider : MainAPI() {
    override val mainUrl = "https://wtr-lab.com"
    override val name = "WTR-LAB"
    override val lang = "en"
    override val iconId = R.drawable.icon_wtrlab
    override val hasMainPage = true
    override val hasReviews = false
    override val usesCloudFlareKiller = true

    //&status=
    override val mainCategories = listOf(
        "All" to "all",
        "Ongoing" to "ongoing",
        "Completed" to "completed"
    )
    //&orderBy=
    override val orderBys =listOf(
        "Date" to "date",
        "Name" to "name",
        "View" to "view",
        "Reader" to "reader",
        "Chapter" to "chapter"
    )
    override val tags = listOf(
        "All" to "",
        "Action" to "1",
        "Adult" to "2",
        "Adventure" to "3",
        "Anime" to "4",
        "Arts" to "5",
        "Comedy" to "6",
        "Drama" to "7",
        "Eastern" to "8",
        "Ecchi" to "ecchi",
        "Fan-fiction" to "9",
        "Fantasy" to "10",
        "Game" to "11",
        "Gender-bender" to "12",
        "Harem" to "13",
        "Historical" to "14",
        "Horror" to "15",
        "Isekai" to "16",
        "Josei" to "17",
        "Lgbt" to "18",
        "Magic" to "19",
        "Magical-realism" to "20",
        "Manhua" to "21",
        "Martial-arts" to "22",
        "Mature" to "23",
        "Mecha" to "24",
        "Military" to "25",
        "Modern-life" to "26",
        "Movies" to "27",
        "Mystery" to "28",
        "Other" to "29",
        "Psychological" to "30",
        "Realistic-fiction" to "31",
        "Reincarnation" to "32",
        "Romance" to "33",
        "School-life" to "34",
        "Sci-fi" to "35",
        "Seinen" to "36",
        "Shoujo" to "37",
        "Shoujo-ai" to "38",
        "Shounen" to "39",
        "Shounen-ai" to "40",
        "Slice-of-life" to "41",
        "Smut" to "42",
        "Sports" to "43",
        "Supernatural" to "44",
        "System" to "45",
        "Tragedy" to "46",
        "Urban" to "47",
        "Urban-life" to "48",
        "Video-games" to "49",
        "War" to "50",
        "Wuxia" to "51",
        "Xianxia" to "52",
        "Xuanhuan" to "53",
        "Yaoi" to "54",
        "Yuri" to "55"
    )


    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url = "$mainUrl/en/novel-list?page=$page&status=$mainCategory&orderBy=$orderBy&genre=$tag"
        val doc = app.get(url).document
        val returnValue =  doc.select(".series-list>div").mapNotNull { select ->
            val titleHolder = select.selectFirst("a") ?: return@mapNotNull null
            val href = titleHolder.attr("href") ?: return@mapNotNull null
            val name = titleHolder.attr("title") ?: return@mapNotNull null
            newSearchResponse(name, href) {
                posterUrl = fixUrlNull(select.selectFirst("a img")?.attr("src"))
            }
        } ?: emptyList()
        return HeadMainPageResponse(url, returnValue)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/en/novel-finder?text=${query.replace(" ", "+")}"
        val doc = app.get(url).document
        return doc.select(".series-list>div").mapNotNull { select ->
            val titleHolder = select.selectFirst("a") ?: return@mapNotNull null
            val href = titleHolder.attr("href") ?: return@mapNotNull null
            val name = titleHolder.attr("title") ?: return@mapNotNull null
            newSearchResponse(name, href) {
                posterUrl = fixUrlNull(select.selectFirst("a img")?.attr("src"))
            }
        } ?: emptyList()
    }


    private suspend fun getChapterRange(
        url: String,
        nextData: ResultJsonResponse.Root,
        start: Long,
        end: Long
    ): List<ChapterData> {
        val chapterDataUrl =
            "$mainUrl/api/chapters/${nextData.props.pageProps.serie!!.serieData.rawId}?start=$start&end=$end"
        val chaptersDataJson =
            app.get(chapterDataUrl).text
        val chaptersData = parseJson<ResultChaptersJsonResponse.Root>(chaptersDataJson)

        return chaptersData.chapters.map { chapter ->
            newChapterData(
                "#${chapter.order} ${chapter.title}",
                "${url.trimEnd('/')}/chapter-${chapter.order}"
            ) {
                dateOfRelease = chapter.updatedAt
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val jsonNode = doc.selectFirst("#__NEXT_DATA__")
        val json = jsonNode?.data() ?: throw ErrorLoadingException("no data")
        val nextData = parseJson<ResultJsonResponse.Root>(json)
        val serie = nextData.props.pageProps.serie ?: throw ErrorLoadingException("no serie")

        val title = serie.serieData.data.title
        val chapters = getChapterRange(
            url,
            nextData,
            1,
            serie.serieData.rawChapterCount
        )

        return newStreamResponse(title, url, chapters) {
            author = serie.serieData.data.author
            synopsis = serie.serieData.data.description
            posterUrl = fixUrlNull(serie.serieData.data.image)
            this.views = serie.serieData.view
            when (serie.serieData.status) {
                0 -> setStatus("Ongoing")
                1 -> setStatus("Completed")
            }
        }
    }


    override suspend fun loadHtml(url: String): String {
        val doc = app.get(url).document
        val jsonNode = doc.selectFirst("#__NEXT_DATA__")
        val json = jsonNode?.data() ?: throw ErrorLoadingException("no data")
        val nextData = parseJson<LoadJsonResponse.Root>(json)
        val text = StringBuilder()
        val chapter = nextData.props.pageProps.serie

        val root = app.post(
            "$mainUrl/api/reader/get", data = mapOf(
                "chapter_id" to chapter.chapter.id.toString(),
                "chapter_no" to (chapter.chapter.slug ?: chapter.serieData.slug),
                "force_retry" to "false",
                "language" to "en",
                "raw_id" to chapter.serieData.rawId.toString(),
                "retry" to "false",
                "translate" to "web",
            )
        ).parsed<LoadJsonResponse2.Root>()

        val paragraphs = decryptContent(root.data.data.body)

        for (p in paragraphs) {
            text.append("<p>")
            text.append(p)
            text.append("</p>")
        }

        return text.toString()
    }

    fun decryptContent(encryptedText: String): List<String> {
        if (encryptedText.isEmpty()) return emptyList()

        var isArray = false
        var rawText = encryptedText

        if (encryptedText.startsWith("arr:")) {
            isArray = true
            rawText = encryptedText.removePrefix("arr:")
        } else if (encryptedText.startsWith("str:")) {
            rawText = encryptedText.removePrefix("str:")
        }

        val parts = rawText.split(":")
        if (parts.size != 3) throw IllegalArgumentException("Invalid format")

        val ivBytes = Base64.decode(parts[0], Base64.DEFAULT)
        val shortCipher = Base64.decode(parts[1], Base64.DEFAULT)
        val longCipher = Base64.decode(parts[2], Base64.DEFAULT)

        val cipherBytes = ByteArray(longCipher.size + shortCipher.size)
        System.arraycopy(longCipher, 0, cipherBytes, 0, longCipher.size)
        System.arraycopy(shortCipher, 0, cipherBytes, longCipher.size, shortCipher.size)

        val keyString = "IJAFUUxjM25hyzL2AZrn0wl7cESED6Ru"
        val keyBytes = keyString.substring(0, 32).toByteArray(Charsets.UTF_8)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(cipherBytes)
        val decryptedText = decryptedBytes.toString(Charsets.UTF_8)

        return if (isArray) {
            parseJson<List<String>>(decryptedText)
        } else {
            listOf(decryptedText)
        }
    }


    object ResultChaptersJsonResponse {
        data class Root(
            val chapters: List<Chapter>,
        )
        data class Chapter(
            @JsonProperty("serie_id")
            val serieId: Long,
            val id: Long,
            val order: Long,
            val title: String,
            val name: String,
            @JsonProperty("updated_at")
            val updatedAt: String,
        )
    }

    object ResultJsonResponse {
        data class Root(
            val props: Props,
        )

        data class Props(
            val pageProps: PageProps,
        )

        data class PageProps(
            val serie: Serie?,
            val series: List<SeriesItem>?,
        )

        data class SeriesItem(
            val id: Long,
            val slug: String,
            val data: Data,
            @JsonProperty("raw_id")
            val rawId: Long,
        )

        data class Serie(
            @JsonProperty("serie_data")
            val serieData: SerieData,
        )

        data class SerieData(
            @JsonProperty("raw_id")
            val rawId: Long,
            val id: Long?,
            val slug: String?,
            val data: Data,
            @JsonProperty("raw_chapter_count")
            val rawChapterCount: Long,
            val view: Int?,
            val status: Int?,
        )

        data class Data(
            val title: String,
            val author: String?,
            val description: String?,
            val image: String?,
        )
    }


    object LoadJsonResponse2 {

        data class Root(
            // val success: Boolean,
            // val chapter: Chapter,
            val data: Data,
        )

        data class Chapter(
            val id: Long,
            @JsonProperty("raw_id")
            val rawId: Long,
            val order: Long,
            val title: String,
        )

        data class Data(
            /*@JsonProperty("raw_id")
            val rawId: Long,
            @JsonProperty("chapter_id")
            val chapterId: Long,
            val status: Long,
            */
            val data: Data2,
            /*@JsonProperty("created_at")
            val createdAt: String,
            val language: String,*/
        )

        data class Data2(
            val body: String = "",
            /*val hans: String,
            val hash: String,
            val model: String,
            val patch: Any?,
            val title: String,
            val prompt: String,
            @JsonProperty("glossory_hash")
            val glossoryHash: String,
            @JsonProperty("glossary_build")
            val glossaryBuild: Long,*/
        )
        data class Terms(
            val terms: List<List<String>>,
        )
    }

    object LoadJsonResponse {
        data class Root(
            val props: Props,
        )

        data class Props(
            val pageProps: PageProps,
        )

        data class PageProps(
            val serie: Serie,
        )

        data class Chapter(
            val id: Long,
            val slug: String?,
            @JsonProperty("raw_id")
            val rawId: Long,
        )

        data class Serie(
            @JsonProperty("serie_data")
            val serieData: SerieData,
            val chapter: Chapter,
        )

        data class SerieData(
            val id: Long,
            val slug: String,
            val data: Data,
            @JsonProperty("raw_id")
            val rawId: Long,
        )

        data class Data(
            val title: String,
            val author: String?,
            val description: String?,
            val image: String?,
        )
    }
}
