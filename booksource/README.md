# booksource —— 搜书 / 下载引擎（第三方工具那一层）

这个模块是**可拔掉的**：它只做"给一个关键词 → 出一列书 → 把一本书抓成一份 txt"。
阅读引擎（`:core`）和界面（`:app`）都不反过来依赖它的内部细节，删掉这个目录，阅读器照常用。

## 为什么单独一个模块

- 它的正确性靠联网，而联网这件事在手机上不可测。所以取值全部离线可测（喂本地 HTML），
  网络那一段是一个接口（`Fetcher`），实现放在 `:app`。
- 它的"内容"是会腐烂的：源站会关搜索、加 CF 盾、把正文用 JS 加密。所以**源是数据文件，不是代码**——
  导入一个 JSON 就是装一个源，删掉就是卸载，不用发版。

## 一条书源 = 一个 JSON

```json5
{
  "id": "bqg", "name": "某某笔趣阁", "homeUrl": "http://.../", "note": "这一条的毛病/备注",
  "charset": "gbk",              // 空 = 先看 HTTP 头，再看页面 meta，最后按 UTF-8
  "kind": "novel",               // novel（默认，可以不写）/ comic（漫画）/ paper（文献）
  "search": {
    "url": "http://.../search.php?kw=%s",   // %s = URL 编码后的关键词，%n = 页码
    "method": "GET",             // 或 POST
    "data": "{submit: Search, kw: %s}",     // POST 请求体，同样过 %s
    "format": "html",            // html / xml（Atom 用 CSS 选择器）/ json（换成点号路径）
    "item": "div.cat > ul > li", // 一条结果一个元素
    "bookName": "span.name > a", // 取值写法见下
    "author": "span.au", "category": "", "latestChapter": "span.new > a",
    "lastUpdateTime": "span.time", "wordCount": "", "intro": "",
    "bookUrl": "span.name > a@href"
  },
  "toc":     { "url": "", "item": "#list dd a", "nextPage": "", "name": "", "reverse": false },
  "chapter": { "content": "#content", "filter": ["\\(本章完\\)", "一秒记住.*?com"], "nextPage": "", "image": "" },
  "crawl":   { "concurrency": 4, "minIntervalMs": 300, "maxIntervalMs": 1200, "maxAttempts": 3 }
}
```

取值字段只有四种写法：

| 写法 | 意思 |
| --- | --- |
| `"css"` | 第一个匹配元素的文字（先取元素自己那截，空了才取整棵子树，免得"书名+作者"整行都成了书名） |
| `"css@href"` | 第一个匹配元素的属性；`href`/`src` 自动按这一页的地址补成绝对链接 |
| `"css@href@url"` | 属性后面再挂一档 `@url`：从取回来那**一整串**里抠出第一个 `http(s)://…` 地址 |
| `""` | 这一项不取 |

`@url` 那一档只为一种站：地址不写在 `href` 本身，而埋在 `href="javascript:PlayBookv(9801,'id','https://x/a.mp3',…)"`
的参数串里。它**不跑 JS**，正则一下就出来；串里没有 `http` 就回空串，调用方按"这一处没抓到"处理，
不交半条假地址下去（判据 `AudioTest.urlSuffixPullsTheAddressOutOfAJavaScriptParameterString`）。

`toc.nextPage` 指向的是那种"第 1/2/3 页"的下拉框（`select#indexselect > option`），
每个 option 的 `value` 填进 `toc.url` 的 `%s`；没有下拉框就留空，只抓当前这一页。

`chapter.nextPage` 是另一回事：一章被切成几页的站（`123.html` → `123_2.html` → …），
这一格写"这一章的下一页"那颗链接的选择器（例：`#pager_next`、`.pagenext`）。正文框里的先试，
框里找不到就整页找 —— 随包的 `shuhaige`（书海阁）就是框外的：`#pager_next` 在页顶、`#A3` 在页尾 `.bottem2`，
两个都在 `#content` 外面，只认框内的话它那一章十页只会下回第一页。
留空 = 这一家一章一页。填了也只跟"同一章的下一页"——目标地址必须和当前页同站、同目录、同扩展名，
文件名只在末尾多出一段 `_数字`/`-数字`；`124.html`、`10.html` 那种是**下一章**，不跟（跟了就等于把整本书并进第一章）；
书海阁末页那颗 `#pager_next` 指向 `/shu_102752.html`（书主页），同样被这一条挡下。
一章最多跟 20 页，到顶还没完就整章记作"没抓到"，不交半截。链接那一行（连它旁边"上一章/返回目录"那一条）
不会留在正文里；站点写在正文最后一行的"这章没有结束，请点击下一页…"之类要靠 `chapter.filter` 去掉（看 `shuhaige.json` 那五条）。

### `kind` = comic：一章 = 一排图

`chapter.content` 可以不写，改写 **`chapter.image`** —— 它选出的是**每一个**匹配（`pickAll`），顺序就是页面上的顺序：

```json5
"kind": "comic",
"chapter": { "image": "img.lazy @data-original" }   // 图库漫画：真地址在 data-original，src 是占位灰图
"chapter": { "image": "img.reading-image @src" }    // 瓜子漫画：就在 src 上
```

这一格**必须带 `@`**：不带的话 `pickAll` 老实回每个元素的**文字**，于是"抓一章图"变成拿那些 alt 当地址去请求 ——
不报错，就是每一章一张也没有。导入时就拒（判据 `anImageSelectorWithoutAnAttributeIsRefusedAtInstall`）。
`kind=comic` 而 `chapter.image` 是空的，同样在导入时就拒。

图片地址是**相对路径**的（`/15/14095/a.jpg`）会按"这一章页面自己的地址"补全。

抓图那一头会带上 `Referer: <这张图所在那一章的页面>` —— 和浏览器点进去时发的那一条一模一样，
不是可配字段。图库漫画的图床实测**不带回 403、带了回 200 的一张 JPEG**，瓜子漫画不查这一格。
字节还要过两道闸：小于 1KB、或者开头不是 JPEG/PNG/WebP/GIF 那四个魔数，都算这一次没抓到（要重试），
因为图床被防盗链挡住时往往回的不是错误码而是一张几十 KB 的占位灰图。

### `kind` = audio：一章（一集）= 一段音频

`chapter.content` 可以不写，改写 **`chapter.audio`** —— 和 `chapter.image` 同一套（`pickAll`，顺序就是页面顺序）：
（**也可以两格都写**：音频和正文在同一页的那一族，抓一集时那一页已经在手上了，顺手把正文一起抽走，
落在同一集目录里的 `text.txt`。抽不到正文**不影响**这一集算成功 —— 见 `AudioTest.anEpisodeWithNoTextIsStillAWholeEpisode`。）：

```json5
"kind": "audio",
"chapter": { "audio": "audio source @src" }        // 地址就写在 src 上
"chapter": { "audio": "source.player @data-src" }  // 懒加载：真地址在 data-src
```

同一族两条闸：这一格**必须带 `@`**（不带就取回 `<audio>` 里那段"您的浏览器不支持"的兜底文字），
也不能留空 —— 都在导入时报，判据 `AudioTest.anAudioRuleWithoutAnAudioSelectorIsRefusedAtInstall`
和 `…SelectorWithoutAnAttributeIsRefusedAtInstall`。相对地址按"这一集页面自己的地址"补全。

取回那段字节过两道闸：小于 4KB、或开头不是 **ID3 / MPEG 帧同步 / `ftyp` / `OggS` / `fLaC` / `RIFF…WAVE`**
那几种魔数之一，就算这一次没抓到（重试到 `maxAttempts`）。要拦的是这种站：会话被踢时回的是 **200 的一张 HTML 登录页**，
状态码看着是成的 —— 存下来就是"每一集都下到了，一集都放不出"。判据 `AudioTest.anHtmlLoginPageIsNeverStoredAsAudioAndTheEpisodeIsReportedMissing`。

**一集的成败按真落地的段数算，不按地址表**：地址写着三段、只到两段，整集记进"缺集"那一栏。
断在半截的一集播到一半就停，听着像"这本就这么长"，比缺一集更难发现。
下次进来 `has()` 会把已有的段跳过、只补缺的那一段（`AudioTest.resumeSkipsPartsAlreadyStored`）。

请求那一段也带 `Referer: <这一集页面自己>`，和漫画同一口径：音频 CDN 里也有按这一格决定给不给的。

落盘的形状在 `:app` 那半边：`filesDir/books/<id>/audio/<集地址 sha1>/parts.tsv` + 按地址 sha1 命名的那些段。

**随包的有声源：两条**。一条是喜马拉雅**公开分发**的播客 RSS（形状见下一节）；
另一条是古文岛（`gushiwen-audio`）那种**同一页既给整回正文、又给整回朗读 mp3** 的古籍有声（形状见再下面那节）——
它是界面上"文字跟着读"那一屏唯一的来源。
还有一条走"自己家 CDN + 签名"的路径（恋听网 ting55）探明了**不能用**，证据链写在主 README 的「有声书」那一节，别再来问第二遍。

### `toc.format` 和 `toc.href`：书页不是 HTML 的那一族

有一类源**每一页不是一种格式**：搜索口回 JSON、书页回 XML（播客 RSS 就是这样）。
所以"解析这一页用哪一档"给了目录/书页单独的一格 `toc.format`（`html` / `xml`，留空 = 跟 `search.format`）。
不写会怎样：**不报错，集名变脏** —— HTML 解析里 `<title>` 是 RCDATA，
`<![CDATA[001 第一回…]]>` 那两层壳会原样留在名字里（判据 `theSameFeedKeepsCdataShellInTitlesWhenParsedAsHtml`）。

`toc.href` 是同一族道理给**链接那一格**的写法：目录条目不是 `<a>`、地址挂在里面另一格时指过去，写法同 `pick`
（`toc.name` 管名字、`toc.href` 管地址）。留空 = 还用条目自己的 `href`，三家随包小说/漫画源都不受影响。

`chapter.audio` 还可以写 **`@self`**：声明"目录里那一条地址本身就是音频文件"，没有第二层集页可点。
这一档下载器**一次集页请求都不发**（发出去就是拿 10MB 的 m4a 当一页 HTML 读回来再解一遍），
Referer 改发书页那一页。判据 `selfAudioDownloadsTheLinkItselfAndNeverAsksForAnEpisodePage`。

一条完整形状（2026-10-14 在 PC 和手机上**两侧都真取到过字节**：同一个地址 3,787,944 字节、
`audio/mp4`、开头 `00 00 00 1c 66 74 79 70 4d 34 41 20` = `ftyp` + `M4A `）：

```json5
{
  "id": "xmly-rss", "name": "喜马拉雅·公开播客", "homeUrl": "https://www.ximalaya.com/", "kind": "audio",
  "search": {
    "url": "https://itunes.apple.com/search?term=%s&media=podcast&limit=10&country=cn", "format": "json",
    "item": "results[]", "bookName": "collectionName", "author": "artistName",
    "bookUrl": "feedUrl", "category": "genres"
  },
  "toc": { "item": "item", "name": "title", "href": "enclosure @url", "format": "xml" },
  "chapter": { "audio": "@self" },
  "crawl": { "concurrency": 1, "minIntervalMs": 300, "maxIntervalMs": 1200, "maxAttempts": 3 }
}
```

搜索走 Apple 的 iTunes 搜索口（它替我们把"哪些专辑有公开 RSS"索引好了），`feedUrl` 就是书页；
书页那份 `.xml` 里每一个 `<item>` 是一集，`<enclosure url>` 就是那一集的地址。
地址外面套了一层 `jt.ximalaya.com`、真文件写在它的 `jt=` 参数里 —— **不用自己抽**：
`HttpURLConnection` 跟得动那一跳 302，`finalUrl` 回来就是 `aod.cos.tx.xmcdn.com/…m4a`，
拿外壳地址打和拿抽出来的直链打，字节数逐位相同（这是量出来的，不是推的）。

**为什么这一条能通而 ting55 不通**：RSS 是播客行业的分发契约 —— Apple 的抓取器就是同款纯 HTTP 客户端，
站点必须让裸请求拿到字节，否则别人的播放器放不了。这一族不是"找到了不设防的站"，是**找到了按协议必须设防不了的地方**。

### 音频和正文在同一页的那一族（`gushiwen-audio`）

上面那一族没有"集页"这一层（`@self`），这一族正相反：**一回的音频地址和整回正文就在同一页上**，
所以 `chapter` 两格都写，抓一集时一次请求两样都拿走：

```json5
{
  "id": "gushiwen-audio", "name": "古文岛·古籍有声", "homeUrl": "https://www.gushiwen.cn/", "kind": "audio",
  "hot": { "url": "https://www.gushiwen.cn/guwen/Default.aspx?p=1&type=%E5%B0%8F%E8%AF%B4%E5%AE%B6%E7%B1%BB",
           "group": "div.sonspic", "item": "div.cont", "bookName": "b",
           "author": "div.book-tag span", "bookUrl": "a[href^\"/guwen/book_\"] @href" },
  "toc": { "item": "a[href^\"/guwen/bookv_\"]" },
  "chapter": { "content": "div.contson",
               "audio": "a[href^=\"javascript:PlayBookv\"] @href@url" },
  "crawl": { "concurrency": 1, "minIntervalMs": 500, "maxIntervalMs": 1500, "maxAttempts": 2 }
}
```

三个不寻常的地方：

- **没有 `search`**：站内搜索 302 到登录页。这一条的入口是「精选 → 有声」（`hot` 指向分类页，实测 10 本）。
  随包那条判据因此不是"每条源都得能搜"，改成"**没有 `search` 的那条必须有 `hot`**" ——
  否则一个真源会被判成坏的（`ShippedRulesTest.everyShippedRuleLoadsAndItsIdMatchesItsFileName`）。
- **地址在 JS 参数串里**：`@href@url` 那一档（见上面取值表）。
- **`content` + `audio` 同时写**：正文和音频一起落盘，落在同一集目录里的 `text.txt`；
  抽不到正文时这一集**仍算成功**（音频才是这本书的主体，缺文字只等于"这一集没得跟读"）。

真站复验的数（2026-10-15）：精选 10 本 → 红楼页目录 **120 回一页给全** → 第一回 **36,357,164 字节**、
开头 `49 44 33 04` = ID3 → 同一页正文 **67 段 / 非空白 7768 格**（`RealSiteFixturesTest.gushiwenChapterGivesItsTextAndItsAudioUrlFromTheSamePage`）。
那一页的 mp3 用 ffprobe 读出 **2272.3 秒** ⇒ 0.2925 秒/格 —— 界面上"文字跟着读"按这一比例摊时刻，
量法与漂移上限写在 `:app` 的 `FollowAlign` 顶上。

### `kind` = paper：一篇 = 一支 PDF

`toc` 和 `chapter` 两段整个可以不写（引擎在这两段是空的时候直接报"这一条源没有目录可读"，
而不是回一本 0 章的书）。`search.format` 决定那一页怎么解：

| format | 页面 | 取值写法 | 随包例子 |
| --- | --- | --- | --- |
| `xml` | Atom（arXiv） | 还是 CSS 选择器：`entry`、`link[title=pdf] @href` | `arxiv.json` |
| `json` | OpenAlex / DOAJ 那种接口 | 点号路径：`results[]`、`authorships[].author.display_name`、`best_oa_location.pdf_url` | `openalex.json` |

`@invert` 是点号路径上唯一的函数，专给 OpenAlex：它不给明文摘要，只给 `abstract_inverted_index`（词 → 位置表），
还原之后如果位置有洞，末尾会写明「摘要读到这里为止」而不是假装拼全了。

### `toc.name` 和 `toc.reverse`

`name`：章名不在那条 `<a>` 自己的文字里、挂在里面那个 `<span>` 上时指过去（瓜子每行后面还跟着「阅读」那颗按钮的字，
不指过去章名就成了"第 1162 话 神之谷大逃杀阅读"）。
`reverse`：站点把目录排成最新在最前时写 `true`。不翻的后果不是难看，是**第 1 章 = 最长那一话**，
进度、"下一章"、听书取句全反着走。这一格是站点的事实，所以写在源里，不在界面上给一颗开关。

## 明确不支持（加载时就拒，不会让你对着一个空列表猜）

- **要跑 JavaScript 的源**：字段里出现 `@js:` 直接拒。这类源要在手机里内嵌一个 V8，
  代价和风险都不在这一层承担。
- **Cloudflare 盾 / 需要代理的站**：手机上过不去，也不假装过得去。
- 字段名写错、必填漏了、同一个键写两遍、限速区间写反、方法不是 GET/POST ——
  全部当场报错并指名是哪一个键。这些如果放过去，表现就是"这条源搜不到东西"，没人会想到是配置文件写坏了。

## 判据怎么跑

```
bash gradlew-local.sh :booksource:test
```

97 条，全离线（`Fetcher` 给脚本化的假页、`sleep` 收进列表里断言、时钟给常数；联网那条打的是本机起的 http 服务）。
其中 28 条要 `src/test/resources/live/` 那批**真站当天抓回的页面**才跑（那批夹具不进仓库，新克隆的机器上按跳过算，
2026-10-13 这台机上是 28 条全跑）：`RealSiteFixturesTest` 19 + `ComicPaperFixturesTest` 9。分类：

- `RuleLoaderTest`（11 条）——**该红的都红**：打错的字段名、多余的键、`@js:`、漏了必填、
  区间反了、方法写歪、JSON 语法歪（重复键、结尾多余内容）。
- `SearchExtractionTest`（9 条）——喂本地 HTML 夹具，断言抽出来的字段一字不差：
  没有链接那一行要丢掉、相对链接要补全、POST 的关键词要同时进地址和请求体、
  明写 `gbk` 的源要真按 GBK 解、`<br>` 和 `<p>` 两种分段都要出段落、水印要被 `filter` 掉、
  限流站那种"不报错但正文是空的"页要交出空串而不是崩、目录有下拉框分页时要按顺序合并且不重复。
- `BookDownloadTest`（9 条）——抓一本的循环：线程谁先跑完不影响成书顺序；限速是**全局累加的发送时刻**
  （常数时钟下四章的等待精确等于 `[0, 800, 1600, 2400]`）；被打回的章重试满 3 次后仍进书；
  正文为空的章重试满之后进 `failed` 而不是当一章空字写进书；断点续抓对已存过的章**一次请求都不发**；
  站点目录只写"1"、或长到超纲时，补出来的标题行仍要能被 `ChapterPattern` 认出来；进度每章回调一次。
- `ComicPaperTest`（10 条，全在合成夹具上）——漫画和文献这两族的取值层：`pickAll` 抽出章页那一排图
  （占位图那张不算）、相对地址按章页补全、`toc.name` 从里面那个 span 取名而不是整条 a（否则章名带上"阅读"）、
  `toc.reverse` 把 newest-first 翻回阅读顺序；`kind` 那一格在导入时验 —— comic 没写 `chapter.image`、
  写了但不带 `@`、`kind` 拼错，三种都要红；paper 那一条允许 `toc`/`chapter` 整段不写，但走 `head()` 要报人话
  而不是回一本 0 章的书。JSON 那一套：点号路径走数组（`authorships[].author.display_name`）、
  `pdf_url` 是 null 的那条不算一条命中（点下去没东西可下）、OpenAlex 那份倒排摘要还原成句子且**有洞就写明**。
  图片那一半数的是真字节：`pages.tsv` 不是图、`.part` 不是图、同一张存两遍还是 1 张。
- `ComicPaperFixturesTest`（9 条，要 `live/` 夹具）——四条随包新源打在真页面上：图库漫画 435 话一次给全且
  第 1 话在最前、那一话写着 28P 就得抽出 28 张、榜单能喂满精选那一排的「漫画」tab；瓜子那本整页 2148 条链接
  只认 `div.all-chapter-grid` 那一格才等于 1068 话不重复、且**引擎交回来的正好是页面倒过来**；
  arXiv 的 `entry` 抽出标题/作者/PDF 直链（不是 abs 页）；OpenAlex 走 JSON 路径且每条的 PDF 地址不重。
- `HttpFetcherTest`（8 条）——真发请求那一层，打的是本机起的 http 服务：UA 必须是浏览器那串
  （默认 `Java/21` 会被站直接 403，表现却像规则写错）、HTTP 头里的字符集要带回去而不是在这里就解、
  非 2xx 要抛（交一张空页回去会被当成"这章正文是空的"，重试就白跑了）、跟完跳转之后的地址才是
  补全相对链接该用的那个、POST 的请求体要按字节原样到。
- `FileChapterStoreTest`（5 条）——落盘那一半：写过的章换个实例（=重启进程）还认得；
  两本书的 `/1.html` 不能串档；键里带中文、斜杠、超长也只落一个文件；
  半截 `.part` 不算一章；空正文不占章位。
- `RealSiteFixturesTest`（11 条）——打在**真站当天抓回的页面**上（`src/test/resources/live/`，六页原样 HTML）：
  搜索行字段一字不差、目录要挑正文那块（挑错段的表现是整本顺序反了而字数照对）、
  正文页要交出 206 段 / 9299 字并且两处站点广告一个字不剩、
  以及一条**反向**的：燃文那种把正文写成 `document.writeln(qsbs.bb('BASE64'))` 的站，抽出来只有 720 字广告 ——
  它不报错，所以只能由这条判据把它按在包外。站点改版这些会红，那正是它该做的工。
- 最后一条端到端：下载器合成那本（486 字）交给**阅读引擎自己的入库**，读回来是
  3 章 / 486 字，一个字不差。那一头 `ChapterPattern` 或 `TxtIngestor` 改了行为，这条会红。
- 取消：`shouldStop` 在每一章动手之前问一次，已抓到的留在仓里，下次点「下载」只补剩下的 ——
  取消不是把线程杀掉，那是假装能停。判据钉的是"单线程下取消后，请求数 = 目录 + 已抓的那一章"。
- 另外 `RuleLoaderTest` 多一条：**地址模板在导入书源时就验**，写歪的（空格、竖线、少个百分号）
  当场报，不放它变成"这本书缺了 40 章"。

夹具是**合成的**（域名、书名都是假的，DOM 形状照真实站那一类）。所以这些测试证明的是
"引擎按规则取得对"，**不证明"某个站今天还能搜"** —— 后者只能在真机上联网验。

## 随包的两条默认源

`src/main/resources/booksource/{mengshu,aixiashu}.json` —— 梦书中文、爱下书小说网（他 2026-10-11 点名要加的那家）。
第一次进「搜书」那一格时拷进 `filesDir/booksource/`，之后就是普通文件：**能关、能删，删了不会自己回来**
（每个名字只种一次，`seeded.<名字>` 记在 prefs 里）。

它三格都是从真站取回的页面判过的（`src/test/resources/live/`，见 `RealSiteFixturesTest`）：
搜「剑来」回 100 行、书页目录按正文顺序 1308 章、正文页抽出 206 段 / 9299 字，
头一段和末一段各按原文钉住，两处站点广告（开头「天才一秒记住本站地址」+网址、结尾「章节错误,点此报送…」）
判它一个字都不剩。所以它不是"抄来的一份名单"，是跑过一遍的；站点改版之后这几条会红。

**别的站为什么没随包**：2026-10-11 那批探测（curl 姿势；python urllib 对这些站一律 502）——

| 站 | 实测 | 为什么不进包 |
|---|---|---|
| 燃文小说网 | 搜 43 行 ✓、目录 100 章 ✓、正文 **4 段 / 720 字** | 正文写成 `document.writeln(qsbs.bb('BASE64'))`，这一层不跑 JS；下载进度能走到 100%，下完每章都是「本站推荐」那串书名 |
| 书海阁 | 搜 24824 字节 ✓、书页 1291 条锚 ✓、正文干净 HTML ✓ | 一章切两页（`id="pager_next" href="…/144629857_2.html"`），跟不跟就是每章丢半截 |
| 悠久小说网 | 搜到 ✓、正文在 `.content` ✓ | 书页上只有「最新章节」13 条，整本目录在别的地址上；而且同样切页 |
| 鸟书网 | 搜 47 处 ✓ | 搜到的那条链接是 `/tag/…`，不是书页 |
| 笔趣阁22 | 搜索端点 **404** | 路径已变 |
| 笔趣阁365 | 3930 字节、**0 结果** | 反爬页 |
| 顶点小说 | **403**；上一轮还吐过 `Illegal mix of collations`（gbk_bin vs utf8mb3） | 中文查询要按站点字符集编码，这条已经会了，但站点这一关没过 |
| 香书 / 笔尖中文 / 阅读库 | 首页 502 或 1400 字节零链接 | 当天不可达 |
| 思思漫画网 `m.sisimanhua.com` | 书页 **811 话的链接都在 HTML 里** ✓、章页 **14,922 字节** | qTcms：章页里 `var qTcms_Pic_curUrl=""` 是空的，HTML 里唯一的"图"是 `https://m.sisimanhua.com/1.png` = **832 字节的占位像素**，其余 34 张是推荐位缩略图（`_small.jpg`）。桌面站一样空。图靠 JS 拼 |
| 开心漫 `www.kaixinman.com` | 书页 121,976 字节 ✓、607 条章链接 ✓ | 章页只有 logo 那一张 `<img>`，图片列表在 `var params = 'IeVAUOAQybt7…'` 那一串 **AES 密文**里；解它的 `/static/js/cms.min.js`(27,395 字节) 被 **jsjiami.com.v7 混淆**，连 `CryptoJS` 的方法名都是运行时算出来的。要吃它就得写一家专用解密 —— 和燃文同一类"进度走到 100%、抓回来是空的"的账，不做 |

**他给过这两条地址（2026-10-13），验完的结论是不装**：那本书《戒魔人》在随包的瓜子漫画上有
（`comic.php?id=17591`，站点自己写 `numberOfItems: 811`，`div.all-chapter-grid` 那一格 HTML 给 808 条，
`toc.reverse` 翻回正序后第一条就是第1话；那一话 18 张图，第一张实测 **200 / 54,711 字节的 JPEG**）。
图库漫画搜「戒魔人」回 0 行。所以下这本走瓜子，别再回来试这两家。

要再多带几条，缺的不是选择器而是**跟着 `_2.html` 把一章读完**这一格 —— 它同时也管着所有用户自己导入的分页站，
那种表现在这里最坏：不报错、字数看着也对、下完才发现每章缺后半截。

第二条（爱下书小说网）也是三格真页面判过的：搜「剑来」回 50 行（表头那行是 6 个 `<th>`、没有 `<a>`，按"没书名就没链接就不算一条"自己就被丢掉）、
`#list` 那个 dl 里两个 dt —— 第一个下面 9 条是倒序，「《剑来》正文」之后 **1307 条**才是正序，
第一章抽出来 **52 段 / 2977 字**、开头是「二月二，龙抬头。」。
它有一处**站点自己的数据错**，明账写在测试里：最后 83 条（第1章 少年游…第83章 今日无事）面包屑仍写「剑来」，
抽出来的正文是另一本现代题材的书（「温沐遥和陈拓呼哧呼哧喘着气」）；往前一条 第1188 章 抽出来 8455 字、有「宁姚」9 次 = 对的。
DOM 里没有任何线索能判，所以这一格没法在规则里治 —— 用这条源下《剑来》会让书尾多出 83 章别人的内容，界面上「随包」二字不代表这本书干净。

许可证那条线：`freeok/so-novel` 是 AGPL-3.0，所以它的规则文件我只当**地图**用（哪些站、搜索 URL 和表单字段长什么样），
随包这份 JSON 是我照真页面自己写的，字段名和文件结构都是本项目的，没搬它的文件本体。
燃文那条留在 `src/test/resources/booksource/`（只当判据的输入，不再打包）。

## 唯一的第三方库

`org.jsoup:jsoup`（Apache-2.0，纯 Java，Android 上直接可用）。自己写 HTML5 解析不是省依赖，
是省不出正确性 —— 这些站的 HTML 一个比一个脏。

另有一条**单向**依赖：`booksource → :core`，只为复用章节标题判据 `ChapterPattern`
（抓回来的书必须能被阅读引擎一章一章分出来，这条契约要在测试里钉住，而不是靠"看着像"）。
`:core` 不知道这个模块存在，删掉 `booksource/` 阅读器照跑。

## 还没做

到这一层，"搜到 → 抓完 → 成书 → 落盘 → 入库"这条链在纯 JVM 侧已经全通了：
`HttpFetcher`（联网）、`FileChapterStore`（断点）、`writeBookText`（成书落盘）、
`BookLibrary.importFile(file, title)`（入库，书名显式传 —— 下载来的那本，书名是搜索命中里那个，
不是我们临时起的 `dl-<时间戳>.txt`）。接上它的那半边界面也在 `:app` 里了（源管理、搜书、下载任务和"接着上次"）。
剩下的：

- `chapter.nextPage` 目前只有 `shuhaige` 一家在用：剑来第52章实测十页，跟回来 10 页 / 18.9k 字；
  同一趟把这一格留空就只剩 1 页 / 1.7k 字，而"没抓到的章"那一栏还是空的 —— 这就是这一格存在的理由。
  另外两家随包源是一章一页，这一格留空。
- 真机上还没从书海阁下过一本书：这一条现在只有 JVM 侧的判据（合成 10 条 + 真页 4 条）。
