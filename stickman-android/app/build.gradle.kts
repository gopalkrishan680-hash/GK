plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each CI build gets a higher version, so a new APK installs over the old one (your projects stay).
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.gopalkrishan.stickmanstudio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gopalkrishan.stickmanstudio"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Signing key comes from the CI secrets (never from the repository). Without it the build is
    // signed with the runner's debug key, which CI caches so updates still install over the old app.
    val ksFile = System.getenv("SS_KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null) {
            create("app") {
                storeFile = file(ksFile)
                storePassword = System.getenv("SS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SS_KEY_ALIAS") ?: "stickman"
                keyPassword = System.getenv("SS_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile != null) signingConfigs.getByName("app") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The app shows the exact same page as the browser version.
    sourceSets {
        getByName("main") {
            assets.srcDirs("../../stickman-studio")
        }
    }
    androidResources {
        ignoreAssetsPattern = "!*.md:!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
