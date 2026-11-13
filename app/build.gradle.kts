import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.stickersearch"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.stickersearch"
        // Android 9+ (API 28): the system decodes animated WebP from there. Newer APIs are version-checked (see compat/).
        minSdk = 28
        targetSdk = 36
        versionCode = 44
        versionName = "1.1.1"
        ndk {
            // 64-bit ARM: nearly every phone since ~2017; other ABIs would add ~100 MB of native ML libraries.
            abiFilters += "arm64-v8a"
        }
    }

    // Play release signing (the "upload key"). The key and its passwords live outside the repo:
    // keystore.properties at the project root (gitignored), or a file named by the
    // STICKERSEARCH_KEYSTORE_PROPERTIES environment variable. Without it, release builds fall back
    // to the debug key, so anyone can still build and sideload.
    val keystoreProps = (System.getenv("STICKERSEARCH_KEYSTORE_PROPERTIES")?.let { file(it) } ?: rootProject.file("keystore.properties"))
        .takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keystoreProps != null) {
            create("upload") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 removes unused code from Compose, Guava, protobuf and the ML libraries (~35 MB of dex → a few MB).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps != null) signingConfigs.getByName("upload") else signingConfigs.getByName("debug")
        }
    }
    packaging {
        jniLibs {
            // Store the ONNX Runtime and MediaPipe libraries compressed: ~60 MB → ~22 MB in the APK.
            // Android extracts them once at install time.
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.all { test ->
            // Optional: -PspModelsDir=<dir> enables tokenizer tests against the real model files.
            providers.gradleProperty("spModelsDir").orNull?.let { test.systemProperty("spModelsDir", it) }
        }
    }
    lint {
        // Sideload-only personal build: MANAGE_EXTERNAL_STORAGE is intentional.
        disable += "ScopedStorage"
    }
}

// Tokenizer files (small, Apache-2.0/MIT) are fetched at build time instead of being committed.
val tokenizerAssets = layout.buildDirectory.dir("generated/tokenizers")
val fetchTokenizers by tasks.registering {
    val out = tokenizerAssets
    outputs.dir(out)
    doLast {
        val files = mapOf(
            "tokenizers/e5.model" to "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/sentencepiece.bpe.model",
            "tokenizers/siglip2.model" to "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/tokenizer.model",
        )
        files.forEach { (rel, url) ->
            val f = out.get().file(rel).asFile
            if (!f.exists() || f.length() == 0L) {
                f.parentFile.mkdirs()
                uri(url).toURL().openStream().use { input -> f.outputStream().use { output -> input.copyTo(output) } }
            }
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(tokenizerAssets)
tasks.named("preBuild") { dependsOn(fetchTokenizers) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.lottie)
    implementation(libs.onnxruntime.android)
    implementation(libs.mediapipe.genai)
    implementation(libs.mediapipe.core)
    testImplementation(libs.junit)
    testImplementation(libs.org.json) // real org.json for JVM tests (Android ships its own)
}
