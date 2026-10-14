package com.bookreader.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.bookreader.core.ingest.EbookText
import com.bookreader.core.ingest.MarkdownText
import com.bookreader.core.ingest.MobiText
import com.bookreader.core.ingest.PptxDoc
import com.bookreader.core.ingest.TxtIngestor
import com.bookreader.core.model.BookMeta
import com.bookreader.core.storage.Book
import com.bookreader.core.storage.IndexCodec
import android.graphics.pdf.PdfRenderer
import java.io.File

/**
 * 书架落在 filesDir/books/<id>/，每目录一本（core 的 BookContainer）。
 * SAF 给的 uri 不是文件路径，所以先流式复制到 cacheDir 再入库，入库产物才进 filesDir。
 *
 * 四条入库路：
 *   txt            → 直接进 [TxtIngestor]
 *   epub / docx    → [EbookText] 抽成"一行一段"的 txt，再进同一个 [TxtIngestor]（章节、偏移、分页全复用）
 *   mobi / azw3    → [MobiText] 解 PalmDOC LZ77，同样抽成 txt 进同一个 [TxtIngestor]
 *   pdf / pptx     → 不进排版引擎：原件留在书目录里，清单上记 kind + 页数，
 *                    打开分别走 PdfReaderScreen（整页位图）和 PptReaderScreen（一屏一张版面）
 */
class BookLibrary(private val context: Context) {

    private val root = File(context.filesDir, "books").apply { mkdirs() }
    private val staging = File(context.cacheDir, "import").apply { mkdirs() }

    fun list(): List<BookMeta> = Book.listBooks(root)

    fun dir(id: String): File = File(root, id)

    fun open(id: String, maxChunks: Int = 6): Book = Book.open(dir(id), maxChunks)

    fun delete(id: String): Boolean = dir(id).deleteRecursively()

    /**
     * 书架上从书源下来的那些本：`BookProvenance.key(源 id, 书页地址)` → 书 id。
     * 点下载之前问一次，用来把"第二次下同一本"接回原来那一本（见 [Downloads.start]）。
     */
    fun downloaded(): Map<String, String> = BookProvenance.downloadedBooks(root)

    /**
     * 改名只动清单里那一行。书名在书架、书签页、阅读页顶栏、听书页、封面占位那十几处都是从
     * manifest 读的，所以这里改一处就处处跟着变 —— 不用每个表面各存一份覆盖值（那样迟早有一处忘了读）。
     */
    fun rename(id: String, title: String): Boolean {
        val f = File(dir(id), IndexCodec.MANIFEST)
        if (!f.isFile) return false
        val m = LinkedHashMap(IndexCodec.readManifest(f))
        m["title"] = title
        IndexCodec.writeManifest(f, m)
        return true
    }

    /** 整页渲染那一类的原件（kind=pdf / kind=pptx 各有一份）。 */
    fun pdfFile(id: String): File = File(dir(id), PDF_FILE)

    fun deckFile(id: String): File = File(dir(id), PPTX_FILE)

    /** 从源站抓回来的那张封面。没抓到的书没这个文件，书架上走默认封面。 */
    fun coverFile(id: String): File = File(dir(id), COVER_FILE)

    /** 漫画那一族的图就在这一层下面：一章一个目录，目录里是那些图 + 一份 `pages.tsv`。 */
    fun comicDir(id: String): File = File(dir(id), COMIC_FILE)

    /**
     * 一本新书的目录名 = id。进度、书签、置顶全按它记，所以这个算法只能有一份：
     * 下载那一头先按它把目录建出来（图要往里写），入库那一头再按它把清单写进同一个目录。
     */
    fun newId(title: String) = "${System.currentTimeMillis()}-${title.replace(UNSAFE, "").take(40)}"

    /**
     * 一本漫画的清单。它没有"抽正文 → 建 chunk/章索引"那一步，图本身就在目录里躺着，
     * 所以这里只写三样：这是什么种类、全书几章（按源站目录，不是按已下的章）、一共多少张图。
     *
     * `chapterCount` 用**整本目录**的章数而不是已下载的章数：「先拉开头」那本只有前十章有图，
     * 书架上那行该写"全书 1068 章"，而阅读页的目录要把没下的那几百章灰着摆出来 ——
     * 少了这个数字，那一本在界面上就只剩十章，像被抓坏了。
     */
    fun writeComicMeta(id: String, title: String, author: String, tocChapters: Int, images: Int): BookMeta {
        val d = dir(id)
        d.mkdirs()
        // 章表（`comic/chapters.tsv`）不在这里写：那一头是 ComicBook.record 每抓完一章 append 一行，
        // 抓一半被杀或按暂停时那一半已经落地了。这里只写清单那一样：种类、全书几章、一共几张图
        val meta = BookMeta(
            id = id,
            title = title,
            author = author,
            totalChars = 0L,
            chunkChars = 0,
            chunkCount = 0,
            chapterCount = tocChapters,
            contentBytes = images.toLong(),
            sourceBytes = 0L,
            sourceCharset = "WEBP/JPEG",
            kind = BookMeta.COMIC,
            pages = images,
        )
        IndexCodec.writeManifest(File(d, IndexCodec.MANIFEST), comicManifest(meta))
        return meta
    }

    private fun comicManifest(m: BookMeta) = linkedMapOf(
        "id" to m.id,
        "title" to m.title,
        "author" to m.author,
        // 漫画没有"字"这个口径，别把张数塞进 totalChars 冒充字数（书架上那行会写成"12 万字"的那种假话）
        "totalChars" to "0",
        "chunkChars" to "0",
        "chunkCount" to "0",
        "chapterCount" to m.chapterCount.toString(),
        "contentBytes" to m.contentBytes.toString(),
        "sourceBytes" to "0",
        "sourceCharset" to m.sourceCharset,
        "kind" to BookMeta.COMIC,
        "pages" to m.pages.toString(),
    )

    fun audioDir(id: String): File = File(dir(id), AUDIO_FILE)

    /**
     * 一本有声书的清单。和漫画同一族：没有"抽正文 → 建 chunk/章索引"那一步，音频本身就在目录里躺着，
     * 这里只写种类、全书几集（按源站目录，不是按已下的集）、一共几段、一共多少字节。
     *
     * 账面那一格和漫画差一处：漫画数"张"，有声书的账面是**字节数**（`contentBytes`）——
     * "这本 1.2 GB"对挑书的人有用，"这本 240 段"只对下载那一头有用，两样各写各的格。
     */
    fun writeAudioMeta(id: String, title: String, author: String, tocChapters: Int, parts: Int, bytes: Long): BookMeta {
        val d = dir(id)
        d.mkdirs()
        // 集表（`audio/chapters.tsv`）不在这里写：那一头是 AudioBook.record 每抓完一集 append 一行，
        // 抓一半被杀或按暂停时那一半已经落地了。这里只写清单那一样
        val meta = BookMeta(
            id = id,
            title = title,
            author = author,
            totalChars = 0L,
            chunkChars = 0,
            chunkCount = 0,
            chapterCount = tocChapters,
            contentBytes = bytes,
            sourceBytes = 0L,
            sourceCharset = "MP3/M4A",
            kind = BookMeta.AUDIO,
            pages = parts,
        )
        IndexCodec.writeManifest(File(d, IndexCodec.MANIFEST), audioManifest(meta))
        return meta
    }

    private fun audioManifest(m: BookMeta) = linkedMapOf(
        "id" to m.id,
        "title" to m.title,
        "author" to m.author,
        // 有声书同样没有"字"这个口径：totalChars 恒 0，大小写在 contentBytes 那一格
        "totalChars" to "0",
        "chunkChars" to "0",
        "chunkCount" to "0",
        "chapterCount" to m.chapterCount.toString(),
        "contentBytes" to m.contentBytes.toString(),
        "sourceBytes" to "0",
        "sourceCharset" to m.sourceCharset,
        "kind" to BookMeta.AUDIO,
        "pages" to m.pages.toString(),
    )

    fun displayName(uri: Uri): String {
        // file:// 没有 DISPLAY_NAME 可查，只能拿路径末段，否则书架上会全叫"未命名小说"
        if (uri.scheme == "file") return uri.lastPathSegment?.substringAfterLast('/') ?: "未命名小说"
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
        return name ?: "未命名小说"
    }

    /** 同步阻塞，调用方要在 IO 线程上跑。 */
    fun import(uri: Uri, onProgress: (Double) -> Unit = {}): BookMeta {
        val name = displayName(uri)
        val ext = name.substringAfterLast('.', "")
        val title = name.substringBeforeLast('.').ifBlank { "未命名小说" }
        val staged = File(staging, "staged-${System.currentTimeMillis()}.${ext.ifBlank { "bin" }}")
        // 输出流必须自己关掉：copyTo 只管往里写，外面那层 64KB 缓冲不 flush，
        // 落盘的件就短一截 —— zip 的中央目录在文件末尾，短了就等于"不是 epub"（真机上量过：
        // 19,624,699 字节的 epub 只剩 19,491,976，被当成纯文本吞成了 99 章乱码）
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "读不到所选文件" }
            staged.outputStream().buffered(1 shl 16).use { out -> input.copyTo(out) }
        }
        return importFile(staged, title, onProgress = onProgress)
    }

    /**
     * 已经是本地一个文件的那条入口：SAF 导入拷完临时件走它，搜书下载下来的整本也走它
     * （下载器交出的就是一份 .txt）。书名在这里显式传：下载来的那本，书名是搜索命中里那个，
     * 不是文件名（文件名是我们临时起的 `dl-1699...txt`）。
     *
     * **这个文件归它管**：入库完就删。库要留的那份已经另存进书目录了，
     * 在 cache 里再留一份只会变成没人清的垃圾。
     */
    /**
     * @param into 传一本书的 id 就是**更新它**：写回同一个目录、同一个 id。
     *   为什么值得留这个口子：进度、书签、置顶全按 id 记在 prefs 里，换新 id 就等于
     *   "更新一本连载"把人已读到的位置抹掉。追加新章不会动前面章节的偏移，所以老位置仍然指得准。
     */
    fun importFile(staged: File, title: String, into: String? = null, onProgress: (Double) -> Unit = {}): BookMeta {
        val ext = staged.extension
        val id = into ?: newId(title)
        val dir = File(root, id)
        if (into != null) {
            // 清掉上一版的中间件再重灌：留着旧的 chunk 会让新书的偏移指到旧字节上。
            // 封面和那三份来源记录不动 —— 更新失败那一次不该顺手把「这本是从哪条源下来的」抹掉，
            // 抹了「补齐全本」那颗就再也找不回书页地址（BookProvenance 的注释早就写了要留，代码之前没做到）
            dir.listFiles()?.forEach { if (it.name !in KEEP_ON_UPDATE) it.delete() }
        }
        val t0 = System.nanoTime()
        return try {
            val ebook = EbookText()
            val mobi = MobiText()
            val meta = when {
                ext.lowercase() == "pdf" -> importPdf(staged, dir, id, title)
                ext.lowercase() == "pptx" -> importPptx(staged, dir, id, title)
                ebook.supports(staged) -> importMarkup(staged, dir, id, title, onProgress) { s, d -> ebook.extractToText(s, d) }
                mobi.supports(staged) -> importMarkup(staged, dir, id, title, onProgress) { s, d -> mobi.extractToText(s, d) }
                // md 只按扩展名认：内容嗅探会把"正文里恰好有三个星号"的小说当 markdown 拆，
                // 那种错看不出来（字数照样对，段落却全乱了），所以宁可漏认也不误认。
                ext.lowercase() in MD_EXTENSIONS -> importMarkdown(staged, dir, id, title, onProgress)
                // 扩展名说是这些格式却没认出壳，就不能当纯文本吞下去：
                // 那会把 zip/palmdb 的二进制读成几十万字乱码（真机上量过：19,491,976 字 / 99 章 / ISO-8859-1）
                ext.lowercase() in BOOK_EXTENSIONS ->
                    error("这个 ${ext.uppercase()} 读不出正文：可能是加密的（DRM/HUFF），或者不是真正的 ${ext.uppercase()} 文件")
                // .ppt 是 97 那版二进制复合文档，不是 zip —— 当纯文本吞会读出几十万字乱码
                ext.lowercase() == "ppt" ->
                    error("这是老版 .ppt（97 二进制格式），这边读不了；在电脑上另存成 .pptx 再导入")
                else -> TxtIngestor().ingest(staged, dir, title = title, onProgress = onProgress).meta
            }
            android.util.Log.d(
                "ReaderPerf",
                "ingest kind=${meta.kind} ext=$ext chars=${meta.totalChars} pages=${meta.pages} " +
                    "chapters=${meta.chapterCount} charset=${meta.sourceCharset} ms=${(System.nanoTime() - t0) / 1_000_000}",
            )
            meta
        } finally {
            staged.delete()
        }
    }

    /**
     * 抽正文那条路（epub / docx / mobi / azw3）：先抽成"一行一段"的 txt（临时件，抽完就删），
     * 再走和 TXT 完全一样的入库 —— 章节识别、分块、偏移、分页全复用，不为哪种壳另起一套。
     */
    private fun importMarkup(
        staged: File,
        dir: File,
        id: String,
        title: String,
        onProgress: (Double) -> Unit,
        extract: (File, File) -> String?,
    ): BookMeta {
        val txt = File(staging, "extracted-${System.currentTimeMillis()}.txt")
        val report = extract(staged, txt) ?: error("这个文件里没抽出正文（可能是加密的）")
        android.util.Log.d("ReaderPerf", "ebook extract $report")
        try {
            return TxtIngestor().ingest(txt, dir, title = title, onProgress = onProgress).meta
        } finally {
            txt.delete()
        }
    }

    /**
     * markdown：抽成纯文本 + 一份样式旁路。样式的偏移是相对抽出来的那段文本算的，
     * 所以 parse 的结果要原样落盘再喂给 TxtIngestor —— 中间多改一个字符，粗体就会粗到别处去。
     */
    private fun importMarkdown(
        staged: File,
        dir: File,
        id: String,
        title: String,
        onProgress: (Double) -> Unit,
    ): BookMeta {
        val parsed = MarkdownText().parseFile(staged)
        val txt = File(staging, "extracted-${System.currentTimeMillis()}.txt")
        txt.writeText(parsed.text, Charsets.UTF_8)
        android.util.Log.d("ReaderPerf", "md 块=${parsed.blocks} 样式=${parsed.spans} 字=${parsed.text.length}")
        try {
            return TxtIngestor().ingest(txt, dir, title = title, styles = parsed.styles, onProgress = onProgress).meta
        } finally {
            txt.delete()
        }
    }

    /**
     * 整页渲染那一类（pdf / pptx）的入库：原件进书目录，清单只记"这是哪一类、多少页"。
     * 页数在入库时就数好 —— 书架和页码条都要它，而渲染器不能跨进程常驻。
     */
    private fun importWholePage(staged: File, dir: File, id: String, title: String, kind: String, file: String, pages: Int): BookMeta {
        require(pages > 0) { "这个 $kind 打不开（0 页）：可能已损坏或被加密" }
        val size = File(dir, file).length()
        IndexCodec.writeManifest(
            File(dir, IndexCodec.MANIFEST),
            linkedMapOf(
                "id" to id,
                "title" to title,
                "author" to "",
                // 整页渲染的书没有"章"和"字"这两个口径，别把页数塞进 chapterCount 冒充
                "totalChars" to "0",
                "chunkChars" to "0",
                "chunkCount" to "0",
                "chapterCount" to "0",
                "contentBytes" to size.toString(),
                "sourceBytes" to size.toString(),
                "sourceCharset" to kind.uppercase(),
                "kind" to kind,
                "pages" to pages.toString(),
            ),
        )
        return BookMeta(
            id = id,
            title = title,
            author = "",
            totalChars = 0L,
            chunkChars = 0,
            chunkCount = 0,
            chapterCount = 0,
            contentBytes = size,
            sourceBytes = size,
            sourceCharset = kind.uppercase(),
            kind = kind,
            pages = pages,
        )
    }

    private fun importPdf(staged: File, dir: File, id: String, title: String): BookMeta {
        dir.mkdirs()
        staged.copyTo(File(dir, PDF_FILE), overwrite = true)
        val pages = runCatching {
            ParcelFileDescriptor.open(File(dir, PDF_FILE), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            }
        }.getOrDefault(0)
        return importWholePage(staged, dir, id, title, BookMeta.PDF, PDF_FILE, pages)
    }

    private fun importPptx(staged: File, dir: File, id: String, title: String): BookMeta {
        dir.mkdirs()
        staged.copyTo(File(dir, PPTX_FILE), overwrite = true)
        val deck = PptxDoc().load(File(dir, PPTX_FILE))
        return importWholePage(staged, dir, id, title, BookMeta.PPTX, PPTX_FILE, deck?.slides?.size ?: 0)
    }

    companion object {
        val UNSAFE = Regex("""[\\/:*?"<>|\s]""")

        /** 这些扩展名一律该走抽正文那条路；认不出壳就报错，不当纯文本吞。 */
        val BOOK_EXTENSIONS = setOf("epub", "docx", "mobi", "azw", "azw3", "prc", "pptx")
        val MD_EXTENSIONS = setOf("md", "markdown", "mdown")
        const val PDF_FILE = "document.pdf"
        const val PPTX_FILE = "deck.pptx"
        const val COVER_FILE = "cover.jpg"
        const val COMIC_FILE = "comic"

        /** 一本有声书的音频都在这一层：`filesDir/books/<id>/audio/<集目录>/`。 */
        const val AUDIO_FILE = "audio"

        /** 一本漫画的章表：`章号  目录名  张数  章名`，一行一章，只有磁盘上真有图的那几章在里面。 */
        const val CHAPTERS_TSV = "chapters.tsv"

        /** 更新整本（`into` 不是空）时不清的那几份：都不是"上一版正文的中间件"。 */
        val KEEP_ON_UPDATE = setOf(COVER_FILE, BookProvenance.SOURCE, BookProvenance.INTRO, BookProvenance.OUTLINE)
    }
}
