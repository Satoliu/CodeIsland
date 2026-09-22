plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.codeisland"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codeisland"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // 只在 CI 上存在。GitHub Secrets 里配好这 4 个值后，
        // 打出来的就是「固定签名」的 release APK，可以直接覆盖安装升级。
        // 没配的话自动退回 debug 签名，照样能装。
        create("release") {
            val storePath = System.getenv("SIGNING_STORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("SIGNING_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // ★ 快捷方式（shortcuts.xml）里的 targetPackage 必须写死成真实的 applicationId，
    //   不能用 ${applicationId} 占位符 —— 那个占位符只在 AndroidManifest 里有效，
    //   放进 res/xml/*.xml 不会被替换，最终会变成一个非法包名，快捷方式点不开。
    //   所以用 resValue 生成一个字符串资源；debug 版会自动带上 .debug 后缀。
    androidComponents {
        onVariants { variant ->
            variant.resValues.put(
                variant.makeResValueKey("string", "shortcut_target_package"),
                com.android.build.api.variant.ResValue(variant.applicationId.get())
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // 只用了 Home / Settings 两个图标，icons-core 就够（10 MB 的
    // material-icons-extended 没必要引）。显式声明一次，
    // 免得将来 material3 不再间接带它时突然编译不过。
    implementation(libs.compose.material.icons.core)

    // API Key 的加密存储
    implementation(libs.androidx.security.crypto)

    // 云端视觉大模型调用
    implementation(libs.okhttp)

    // 本地离线 OCR（不带中文包，中文包下载后才生效，见 MlKitRecognizer）
    implementation(libs.mlkit.text.recognition.chinese)
}
