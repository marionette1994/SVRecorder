plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.marionette1994.svrec"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.marionette1994.svrec"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNo
        versionName = "1.0.$buildNo"
    }

    // 毎回同じ鍵で署名して、上書きアップデートできるようにする
    signingConfigs {
        create("fixed") {
            storeFile = file("signing.keystore")
            storePassword = "svrecorder"
            keyAlias = "svrec"
            keyPassword = "svrecorder"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // 端末内で動く日本語OCR（ネット不要）
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
}
