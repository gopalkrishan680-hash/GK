plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gk.speakup"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gk.speakup"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs directly on your phone.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.md", "META-INF/*.txt", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/*.kotlin_module",
                "META-INF/versions/**", "META-INF/io.netty.versions.properties",
            )
        }
    }
}

dependencies {
    // Claude (Anthropic) official Java/Kotlin SDK
    implementation("com.anthropic:anthropic-java:3.6.0")
    // HTTP client for the Google Gemini REST API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
