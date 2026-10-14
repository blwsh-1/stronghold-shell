plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stronghold.shell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stronghold.shell"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // 只保留 arm64（与游戏前端一致，体积最小）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 允许不压缩 app_bundle.zip，加速打包（可选）
    androidResources {
        noCompress += listOf("zip")
    }

    // 我们把预编译的 libnode.so 放到 app/src/main/jniLibs/arm64-v8a/
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    // ===== 固定签名配置 =====
    // 有 keystore（app/stronghold.jks，由 CI 从 Secrets 还原）时用它签名，
    // 保证每次构建证书一致 → 可覆盖升级、不丢已导入的游戏包。
    // 没有则回退 debug 签名（本地随手构建用）。
    val ksFile = rootProject.file("app/stronghold.jks")
    signingConfigs {
        if (ksFile.exists()) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("KEY_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有固定 keystore 就用它；否则退回 debug（仅本地应急）
            signingConfig = if (ksFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // assets 默认会打包 app/src/main/assets 下的所有文件，
    // 我们把游戏前端打成 assets/app_bundle.zip 放进去即可。
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 显式声明 activity（registerForActivityResult / 文件选择器需要）
    implementation("androidx.activity:activity-ktx:1.9.2")

    // ⭐ JNA：直接调用 libnode.so 的 node::Start（与现有壳同款做法）
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}