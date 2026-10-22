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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

// :core:test 一次全量要往 temp 写几 GB 夹具（Fixtures.tempRoot），别让它们落在系统盘。
// 路径读本机的 local.properties（已 gitignore），没配就跟 JVM 默认一样，干净克隆的行为不变。
// 不写 java.util.Properties：这里的 `java` 已被解析成 java 扩展，那个前缀在 .kts 里点不出来。
val testTmpDir = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.readLines()
    ?.firstOrNull { it.startsWith("test.tmp.dir=") }
    ?.substringAfter("=")
    ?.trim()

tasks.withType<Test>().configureEach {
    maxHeapSize = "512m"
    if (testTmpDir != null) systemProperty("java.io.tmpdir", testTmpDir)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
