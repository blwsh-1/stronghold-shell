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
        versionName = "0.1.0"
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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 用 debug 签名即可自用；正式发布请换 keystore
            signingConfig = signingConfigs.getByName("debug")
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

    // ⭐ JNA：直接调用 libnode.so 的 node::Start（与现有壳同款做法）
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}