package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.ArrayList


class Gomunimeis : MainAPI() {
    override var mainUrl = "https://gomunime.is"
    override var name = "Gomunime.is"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {

        private const val mainImageUrl = "https://upload.anoboy.live"

        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "&limit=12&action=load_movie_last_update&status=Ongoing" to "Episode Baru",
        "&limit=15&action=load_movie_last_update&status=Completed" to "Completed",
        "&limit=15&action=load_movie_last_update&type=Live Action" to "Live Action",
        "&limit=15&action=load_movie_trending" to "Trending"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = app.get(
            "$mainUrl/my-ajax?page=$page${request.data}",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        )
            .parsedSafe<Responses>()?.data
            ?.mapNotNull { media ->
                media.toSearchResponse()
            } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    private fun Anime.toSearchResponse(): SearchResponse? {

        return newAnimeSearchResponse(
            postTitle ?: return null,
            "$mainUrl/anime/$postName.$salt",
            TvType.TvSeries,
        ) {
            this.posterUrl = "$mainImageUrl/$image"
            addSub(totalEpisode?.toIntOrNull())
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get(
            "$mainUrl/my-ajax?page=1&limit=10&action=load_search_movie&keyword=$query",
            referer = "$mainUrl/search/?keyword=$query",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Responses>()?.data
            ?.mapNotNull { media ->
                media.toSearchResponse()
            } ?: throw ErrorLoadingException("Invalid Json reponse")
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".entry-title")?.text().toString()
        val poster = document.selectFirst(".thumbposter > img")?.attr("src")
        val tags = document.select(".genxed > a").map { it.text() }
        val type = getType(document.selectFirst("div.info-content .spe span:last-child")?.ownText().toString())
        val year = Regex("\\d, ([0-9]*)").find(
            document.selectFirst("div.info-content .spe span.split")?.ownText().toString()
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(document.selectFirst(".spe > span")!!.ownText())
        val description = document.select("div[itemprop = description] > p").text()

        val episodes = document.select(".eplister > ul > li").map {
            val header = it.select(".epl-title").text()
            val name = Regex("(Episode\\s?[0-9]+)").find(header)?.groupValues?.getOrNull(0) ?: header
            val link = it.select("a").attr("href")
            Episode(link, name)
        }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        document.select("div.player-container iframe").attr("src").substringAfter("html#").let { id ->
            app.get("https://gomunimes.com/stream?id=$id").parsedSafe<Sources>()?.server?.streamsb?.link?.let { link ->
                loadExtractor(link, "https://vidgomunime.xyz/", subtitleCallback, callback)
            }
        }

        return true
    }

    data class Streamsb(
        @JsonProperty("link") val link: String?,
    )

    data class Server(
        @JsonProperty("streamsb") val streamsb: Streamsb?,
    )

    data class Sources(
        @JsonProperty("server") val server: Server?,
    )

    data class Responses(
        @JsonProperty("data") val data: ArrayList<Anime>? = arrayListOf(),
    )

    data class Anime(
        @JsonProperty("post_title") val postTitle: String?,
        @JsonProperty("post_name") val postName: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("total_episode") val totalEpisode: String?,
        @JsonProperty("salt") val salt: String?,
    )

}