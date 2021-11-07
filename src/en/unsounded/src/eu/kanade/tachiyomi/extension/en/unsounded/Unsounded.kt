package eu.kanade.tachiyomi.extension.en.unsounded

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import rx.Observable

/**
 */

class Unsounded : HttpSource() {

    override val name = "Unsounded"

    override val baseUrl = "https://www.casualvillain.com/Unsounded"

    override val lang = "en"

    override val supportsLatest: Boolean = false

    private lateinit var indexDoc: Document

    // List the archive and the latest chapter
    override fun popularMangaParse(response: Response): MangasPage {
        indexDoc = response.asJsoup()

        val mangas = listOf(
            createArchiveManga(),
            createLatestChapterManga()
        )

        return MangasPage(mangas, false)
    }

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/comic+index/")
    }

    private fun createArchiveManga(): SManga {
        val archDesc = "\n\nAll chapters except the latest, updated only when a chapter ends."
        return SManga.create().apply {
            title = "Unsounded (archive)"
            artist = "Ashley Cope"
            author = "Ashley Cope"
            status = SManga.COMPLETED
            // The URL is supposed to be the unique identifier for each manga, so let's provide
            // something that acts as a unique ID...
            url = "UnsoundedArchive"
            genre = "Epic, Fantasy, Adventure"
            description = (getDescription() + archDesc)
            thumbnail_url = "$baseUrl/comic/ch01/pageart/ch01_01.jpg"
            initialized = true
        }
    }
    private fun createLatestChapterManga(): SManga {
        val latestChapterNum = getChapterList().size
        val latestChapterTitle = getChapterTitle(latestChapterNum)
        val latestDesc = "\n\nLatest chapter of Unsounded, updated with every new page."
        return SManga.create().apply {
            title = "Unsounded $latestChapterTitle"
            artist = "Ashley Cope"
            author = "Ashley Cope"
            status = SManga.ONGOING
            url = "UnsoundedLatest"
            genre = "Epic, Fantasy, Adventure"
            description = (getDescription() + latestDesc)
            thumbnail_url = "$baseUrl/comic/ch${String.format("%2d", latestChapterNum)}/pageart/ch${String.format("%2d", latestChapterNum)}_01.jpg"
            initialized = true
        }
    }

    private fun getDescription(): String {
        val descPage = client.newCall(GET("$baseUrl/about.html")).execute().asJsoup()
        val descElement = descPage.select("#main_content")[ 0 ]
        // try and strip at least some of this whitespace
        return descElement.text()
            .trim()
            .replace("\n ", "\n")
            .replace("\n\n\n", "\n\n")
    }

    private fun getChapterTitle(chap: Int): String {
        // Note: Not zero-indexed; pass in 1 to get Chapter 1 etc.
        val chapList = getChapterList()
        if (chap > chapList.size) {
            return ""
        }
        // remember, chapList is reversed
        val index = chapList.size - chap
        return chapList[ index ].getElementsMatchingOwnText("""Chapter \d\d:""")[ 0 ].ownText()
    }

    private fun getChapterList(): Elements {
        // List is returned with latest chapter first due to page arrangement
        // she has a 'chapter_box7' instead of a 'chapter_box' on Chapter 7...
        return indexDoc.getElementsByAttributeValueContaining("id", "chapter_box")
    }

    // Latest Updates not used
    override fun latestUpdatesParse(response: Response): MangasPage = throw Exception("Not used")
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return fetchPopularManga(1).map { it.mangas.find { sManga -> sManga.url == manga.url } }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        // don't make *another* request if we've already gotten the chapter list page...
        if (!this::indexDoc.isInitialized) {
            indexDoc = client.newCall(popularMangaRequest(1)).execute().asJsoup()
        }
        return when (manga.url) {
            "UnsoundedArchive" -> { Observable.just(archiveChapterListParse()) }
            "UnsoundedLatestChapter" -> { Observable.just(latestChapterListParse()) }
            else -> { Observable.empty() }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = popularMangaRequest(1)

    override fun chapterListParse(response: Response): List<SChapter> = throw Exception("Not used")

    private fun archiveChapterListParse(): List<SChapter> {
        // chapter list is already last on top
        val chapList = getChapterList().subList(1, getChapterList().size)
        var chapNum = chapList.size
        val retList = mutableListOf<SChapter>()

        for (chapData in chapList) {
            retList.add(
                SChapter.create().apply {
                    chapter_number = chapNum.toFloat()
                    url = "ArchiveChap"
                    name = getChapterTitle(chapNum)
                    date_upload = System.currentTimeMillis()
                }
            )
            chapNum--
        }
        return retList
    }

    private fun getFormattedChapterNum(num: Int): String {
        return String.format("%02d", num)
    }

    private fun latestChapterListParse(): List<SChapter> {
        val pageCount = getPageCountFromChapterBox(getChapterList().size)
        val retList = mutableListOf<SChapter>()
        for (pageI in 0..pageCount) {
            retList.add(
                SChapter.create().apply {
                    url = "LatestChap"
                    name = "Page ${getFormattedChapterNum(pageI + 1) }"
                    date_upload = System.currentTimeMillis()
                    chapter_number = (pageI + 1).toFloat()
                }
            )
        }
        return retList.reversed()
    }

    private fun getPageCountFromChapterBox(chapNum: Int): Int {
        val chapData = getChapterList()[ chapNum - 1 ]
        return chapData.getElementsByAttributeValueMatching("href", """.*comic/ch\d\d/ch\d\d_\d\d.html""").size
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val chapNum = chapter.chapter_number.toInt()
        if (!this::indexDoc.isInitialized) {
            indexDoc = client.newCall(popularMangaRequest(1)).execute().asJsoup()
        }
        when (chapter.url) {
            "ArchiveChap" -> {
                val retList = mutableListOf<Page>()
                for (pageI in 0..getPageCountFromChapterBox(chapNum)) {
                    retList.add(
                        Page(
                            pageI, "ch${getFormattedChapterNum(chapNum)}_${getFormattedChapterNum(pageI)}",
                            getImageUrl(chapNum, pageI), null
                        )
                    )
                }
                return Observable.just(retList)
            }
            "LatestChap" -> {
                return Observable.just(
                    listOf(
                        Page(
                            0, "ch${getFormattedChapterNum(getChapterList().size)}_${getFormattedChapterNum(chapNum)}",
                            getImageUrl(getChapterList().size, chapNum), null
                        )
                    )
                )
                // now say that five times fast
            }
            else -> {
                return Observable.just(emptyList())
            }
        }
    }

    private fun getImageUrl(chapNum: Int, pageNum: Int): String {
        return "$baseUrl/ch${getFormattedChapterNum(chapNum)}/pageart/ch${getFormattedChapterNum(chapNum)}_${getFormattedChapterNum(pageNum)}.jpg"
    }

    override fun imageUrlParse(response: Response): String = throw Exception("Not used")

    override fun mangaDetailsParse(response: Response): SManga = throw Exception("Not used")
    override fun pageListParse(response: Response): List<Page> = throw Exception("Not used")

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return Observable.just(MangasPage(emptyList(), false))
    }

    override fun searchMangaParse(response: Response): MangasPage = throw Exception("Not used")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw Exception("Not used")
}
