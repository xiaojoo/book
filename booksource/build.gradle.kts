plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 单向依赖：下载器要复用阅读引擎的章节标题判据（ChapterPattern），保证"抓回来的书"
    // 在引擎里真能一章一章分出来。:core 不知道这个模块存在，删掉 booksource 阅读器照跑。
    implementation(project(":core"))
    // 唯一的外部库：jsoup（Apache-2.0，纯 Java 无原生层，Android 上可直接用）。
    // 自己写 HTML5 分词器不是省依赖，是省不出正确性 —— 网上小说站的 HTML 一个比一个脏。
    implementation("org.jsoup:jsoup:1.22.2")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "512m"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
