import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Offline speech model for the "Hi Jarvis" wake word (~40 MB, Apache 2.0).
// Downloaded at build time and bundled, so the app works with no extra setup.
val voskModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

val downloadVoskModel by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/voskAssets")
    outputs.dir(outDir)
    doLast {
        val zip = outDir.get().file("vosk-model.zip").asFile
        if (!zip.exists() || zip.length() < 1_000_000) {
            zip.parentFile.mkdirs()
            logger.lifecycle("Downloading Vosk model from $voskModelUrl")
            URI(voskModelUrl).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        }
    }
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.$versionCode"
    }

    signingConfigs {
        // CI signs with a stable key when the JARVIS_KEYSTORE_* secrets are set, so
        // new versions install over old ones. Otherwise the usual debug key is used.
        val keystore = System.getenv("JARVIS_KEYSTORE_FILE")
        if (keystore != null && file(keystore).exists()) {
            create("stable") {
                storeFile = file(keystore)
                storePassword = System.getenv("JARVIS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JARVIS_KEY_ALIAS") ?: "jarvis"
                keyPassword = System.getenv("JARVIS_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].assets.srcDir(downloadVoskModel)

    androidResources {
        noCompress += "zip"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/9/module-info.class",
                "module-info.class",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}

tasks.named("preBuild") { dependsOn(downloadVoskModel) }
