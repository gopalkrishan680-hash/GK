import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gk.meriawaaz"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gk.meriawaaz"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // A fixed key kept in the repo so that every build can be installed over the previous one.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("signing/meri-awaaz.jks")
            storePassword = "meriawaaz"
            keyAlias = "meriawaaz"
            keyPassword = "meriawaaz"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "onnx" }
    lint { checkReleaseBuilds = false }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("androidx.core:core-ktx:1.15.0")
}

// The models are produced by tools/export_onnx.py and are not stored in git.
val checkModels by tasks.registering {
    val assets = layout.projectDirectory.dir("src/main/assets")
    doLast {
        listOf("tone_encoder.onnx", "converter.onnx").forEach {
            check(assets.file(it).asFile.length() > 1_000_000) {
                "Missing model app/src/main/assets/$it - run tools/export_onnx.py first (see README)."
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(checkModels) }
