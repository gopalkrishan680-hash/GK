import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val ortVersion = "1.22.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The app supplies onnxruntime-android; both artifacts expose the same ai.onnxruntime API.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    // Model tests are skipped unless these point at exported models / test recordings.
    listOf("MERI_AWAAZ_MODELS", "MERI_AWAAZ_WAVS").forEach { k ->
        System.getenv(k)?.let { environment(k, it) }
    }
    inputs.property("models", System.getenv("MERI_AWAAZ_MODELS") ?: "")
    inputs.property("wavs", System.getenv("MERI_AWAAZ_WAVS") ?: "")
}
