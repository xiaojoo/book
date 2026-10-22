import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布签名读根目录的 keystore.properties；没有就退回 debug 签名，
// 这样仓库里不带密钥也能构建出可安装的 release 包（只是不能覆盖升级）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.bookreader"
    compileSdk = 37

    signingConfigs {
        create("release") {
            storeFile = keystoreProps["storeFile"]
                ?.let { file(it as String) }
            storePassword = keystoreProps["storePassword"] as String?
            keyAlias = keystoreProps["keyAlias"] as String?
            keyPassword = keystoreProps["keyPassword"] as String?
        }
    }

    defaultConfig {
        applicationId = "com.bookreader"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            signingConfig = if (keystoreProps.isEmpty())
                signingConfigs.getByName("debug")
            else signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":core"))
    // 搜书/下载那一层（纯 JVM，可拔掉）：界面只认它的 Rule 和 DownloadOutcome
    implementation(project(":booksource"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // 下载要能活过"切走 / 锁屏 / 进程被杀"，官方那条路是 WorkManager（+ 前台进度）。
    // 自己不写重试和重启恢复：那是它的一半实现。
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    testImplementation("junit:junit:4.13.2")
}
