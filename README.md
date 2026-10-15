# 长文阅读

千万字级 TXT 的阅读引擎 + Android 原生壳。按 `Chunk → Layout → Page` 分页，正文永远只按逻辑块随机读，
内存里只留当前阅读窗口附近的几块；阅读位置存字符偏移，不存页码。

## 安装

需要准备：JDK 21、Android SDK（`compileSdk` 37）、Gradle 9.7.1。仓库里没有 wrapper jar，
`gradlew-local.sh` 是本机用的便捷脚本 —— 它把 `JAVA_HOME` 指到 JDK 21，再直接执行 Gradle 缓存里已解压的
9.7.1 发行版。换一台机就把它里面两行路径改成你自己的，或者换成任意一条能跑起 Gradle 9.7.1 的命令，
后面四行的任务名不变。

`local.properties`（已 gitignore）要写 `sdk.dir=`；`test.tmp.dir=` 可选，指到空间大的盘 ——
`:core:test` 一次全量要往 temp 写几 GB 夹具，不指就落系统 temp。

```bash
./gradlew-local.sh :core:test              # 阅读引擎（纯 JVM）
./gradlew-local.sh :booksource:test        # 搜书 / 下载引擎
./gradlew-local.sh :app:testDebugUnitTest  # 界面层单测
./gradlew-local.sh :app:assembleDebug      # APK → app/build/outputs/apk/debug/app-debug.apk
```

装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# MIUI 挡 adb install 时改走：adb push ... /data/local/tmp/x.apk && adb shell pm install -r /data/local/tmp/x.apk
```

## 技术栈

三个模块，依赖单向：`:app → (:core, :booksource)`、`:booksource → :core`。
`:core` 不知道另外两个存在，删掉 `booksource/` 阅读器照跑。

| 模块 | 语言 / 平台 | 外部依赖 | 负责什么 |
| --- | --- | --- | --- |
| `:core` | Kotlin 2.4.20，纯 JVM（source/target 17），源码零 `import android` | `kotlinx-coroutines-core` 1.9.0 | 解码与字符集判定、章节识别、切块、排版、分页、搜索；epub / docx / mobi·azw3 / markdown 抽正文；pptx 解析 |
| `:booksource` | Kotlin 2.4.20，纯 JVM（17） | `jsoup` 1.22.2 | 搜书 / 下载。一条源 = 一个 JSON 规则文件，不是代码 |
| `:app` | Kotlin 2.4.20 + Compose（source/target 21） | Compose BOM 2026.09.00（ui / ui-graphics / foundation / material3 / ui-tooling-preview）、`activity-compose` 1.13.0、`core-ktx` 1.19.1、`work-runtime-ktx` 2.12.0 | 外壳四页、阅读器、漫画 / 听书 / 文献各屏、下载前台任务 |

- 构建：AGP 9.4.1，Gradle 9.7.1，`compileSdk` / `targetSdk` 37，`minSdk` 26，`android.nonTransitiveRClass=true`。
- 排版测量：设备上用 `Paint.breakText` 真量（走 minikin 的 Unicode 断行表），JVM 测试用近似字宽模型，
  所以分页算法在没有手机时也能被数字否证。Compose 每行拿一次 `drawText`，一行不是一个 Compose 节点。
- pdf / pptx 是整页渲染那一条路，和抽正文的五种格式分开：pdf 用平台 `android.graphics.pdf.PdfRenderer`，
  pptx 自己解 OOXML（`java.util.zip`）。
- 音频播放用平台 `android.media.MediaPlayer`（倍速走 `playbackParams`）。
- 下载要活过「切走 / 锁屏 / 进程被杀」，走官方 `WorkManager` 的前台那一档，重试和重启恢复交给它。
- 测试：JUnit 4.13.2。`@Test` 计数为 `:core` 100 / `:booksource` 118 / `:app` 70（`:core` 里无 `@Ignore`）；
  `:core` 测试堆 512m。
- 除此之外没有别的第三方库：HTTP 是 `java.net.HttpURLConnection`，图片是 `BitmapFactory`，
  持久化是 `SharedPreferences` + 自建的索引 / 缓存文件，不引 OkHttp、Coil、Room。

## 声明

- 个人学习与技术验证项目，**不含任何内置书籍内容**：txt / epub / docx / mobi / markdown / pdf / pptx 全部由用户自己导入；
  小说正文、漫画的分页图、有声书的音频都是运行时从第三方站点抓回来的。
- 联网只发生在这几条链路：搜书与下载、词典、AI 合成语音与 AI 翻译。读本地文件、入库、排版、翻页全程不联网
  —— 清单里那条 `INTERNET` 权限是给前几条用的，`FOREGROUND_SERVICE_DATA_SYNC` 和 `POST_NOTIFICATIONS` 是给下载进度的。
- `booksource/src/main/resources/booksource/` 里随包的 9 条源加一份 `template.json`，只是指向第三方站点的取数规则，
  本项目不托管任何正文。站点内容的版权归原站和原作者；能否抓取、是否应当抓取由使用者自行判断，责任也在使用者。
- 自建中转 / 内网 Ollama 那类地址支持 `http://` 明文（清单里的 `usesCleartextTraffic`），代价是密钥和正文都明文走网，
  所以界面在填 http 地址时必须把这件事标出来。AI 相关的密钥只存本机，不上传、不进仓库。
- 界面**不内置任何书籍封面图**：早期从别人给的参考截图里裁出来的那批占位封面（`cover_feat1..6.png`、`cover_jianlai.png`）
  已在提交 `3ac9d8a` 全部删掉，`app/src/main/res` 下现在一张位图都没有。封面两档：源站抓回来的 `cover.jpg`
  （`BookLibrary.COVER_FILE`，落在应用自己的目录里），或自绘的默认封面 —— 底色按书名稳定哈希取（`coverSwatch`），
  上面把书名整个横排（最多 4 行、超出截尾），左边一条 4dp 的书脊。
- 本仓库**目前没有 LICENSE 文件**，也未声明任何开源许可证；在此之前默认保留所有权利，不代表可以被自由使用或分发。

## 文档

- [`docs/界面与功能.md`](docs/界面与功能.md) —— 外壳四页、各格式与每条功能的做法（含像素读数）
- [`docs/设计与实测.md`](docs/设计与实测.md) —— 引擎设计要点，以及 JVM / 真机两边的实测数字
- [`docs/缺点与限制.md`](docs/缺点与限制.md) —— 已知缺陷、没走到的验收、刻意没做的部分
- [`开发记录.md`](开发记录.md) —— 逐轮的开发问答与解决记录
- [`booksource/README.md`](booksource/README.md) —— 书源 JSON 规则怎么写
