plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each build gets a higher version, so a new APK installs over the old one (your projects stay).
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: System.getenv("VOX_BUILD") ?: "1").toInt()

android {
    namespace = "com.gopalkrishan.voxstudio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gopalkrishan.voxstudio"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Signing key comes from the environment (never from the repository). Without it the build is
    // signed with the debug key.
    val ksFile = System.getenv("VOX_KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null) {
            create("app") {
                storeFile = file(ksFile)
                storePassword = System.getenv("VOX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VOX_KEY_ALIAS") ?: "vox"
                keyPassword = System.getenv("VOX_KEYSTORE_PASSWORD")
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

    // The app shows the exact same page as the browser version (vox-studio/Vox-Studio.html).
    sourceSets {
        getByName("main") {
            assets.srcDirs("build/generated/vox-assets")
        }
    }
}

// copy the page into the app as assets/index.html
val copyPage by tasks.registering(Copy::class) {
    from("../../vox-studio/Vox-Studio.html") { rename { "index.html" } }
    into("build/generated/vox-assets")
}
tasks.named("preBuild") { dependsOn(copyPage) }

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
