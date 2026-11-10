import java.util.Properties
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Nothing personal is built in: the box, its ports, tokens and hosts come from pairing (otto pair). The one
// build-time option is otto.extraLinkHosts: hosts outside *.ts.net whose artifact links the app should open,
// such as an artifact hub's public domain (Android only lets an app claim the hosts its manifest lists).
// Set it with -Potto.extraLinkHosts=a.example.com,b.example.com, or in local.properties (not committed).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val extraLinkHosts: List<String> = ((findProperty("otto.extraLinkHosts") as String?)?.takeIf { it.isNotBlank() }
    ?: localProps.getProperty("otto.extraLinkHosts") ?: "")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "io.github.otto"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.otto"
        minSdk = 31
        targetSdk = 35
        versionCode = (findProperty("versionCode") ?: "1").toString().toInt()
        versionName = (findProperty("versionName") ?: "0.1").toString()
        buildConfigField("String", "EXTRA_LINK_HOSTS", "\"${extraLinkHosts.joinToString(",")}\"")
        manifestPlaceholders["appLabel"] = "Otto"
        manifestPlaceholders["pairScheme"] = "otto"
        buildConfigField("String", "PAIR_SCHEME", "\"otto\"")
        // -Pabi=arm64-v8a builds for that one CPU only (a smaller, quicker build for one phone).
        (findProperty("abi") as String?)?.let { ndk { abiFilters += it.split(",") } }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    // Release signing from the environment (OTTO_KEYSTORE and friends); otherwise the debug key.
    signingConfigs {
        create("release") {
            System.getenv("OTTO_KEYSTORE")?.let { ks ->
                storeFile = file(ks)
                storePassword = System.getenv("OTTO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OTTO_KEY_ALIAS") ?: "otto"
                keyPassword = System.getenv("OTTO_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        // The dev build: "Otto Dev", its own package beside the stable app, unshrunk (quick to build), with its
        // own icon and a lavender handle (src/debug). It pairs with a box's dev relay (otto pair, OTTO_CONFIG).
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "Otto Dev"
            // Its own links, so the stable app keeps "always open with": otto-dev://pair, and https links under /dev/.
            manifestPlaceholders["pairScheme"] = "otto-dev"
            buildConfigField("String", "PAIR_SCHEME", "\"otto-dev\"")
        }
        release {
            signingConfig = signingConfigs.getByName(if (System.getenv("OTTO_KEYSTORE") != null) "release" else "debug")
            // R8: Compose, Media3 and the rest come to a third of their size shrunk.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources {
        // Keep the web UI's files whatever their names (the default skips some, e.g. ones starting with _).
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
}

// The extra link hosts go into the manifest as one more intent filter, generated from the real one.
if (extraLinkHosts.isNotEmpty()) {
    val source = file("src/main/AndroidManifest.xml")
    val generated = layout.buildDirectory.file("generated/otto-manifest/AndroidManifest.xml").get().asFile
    val hosts = extraLinkHosts.joinToString("\n") { "                <data android:host=\"$it\" />" }
    val filter = """
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" />
$hosts
                <data android:pathPrefix="/a/" />
                <data android:pathPrefix="/p/" />
                <data android:pathPrefix="/v/" />
            </intent-filter>"""
    val text = source.readText()
    val marker = "<!-- otto.extraLinkHosts -->"
    check(marker in text) { "$marker is missing from the manifest" }
    generated.parentFile.mkdirs()
    generated.writeText(text.replace(marker, filter.trimStart()))
    android.sourceSets.getByName("main").manifest.srcFile(generated)
}

// The web UI (Files, Artifacts, Desktop, Terminal, Devbox) is otto/web, copied into the APK's assets at ide/.
abstract class SyncWeb : DefaultTask() {
    @get:InputDirectory
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun run() {
        files.sync {
            from(source)
            into(output.dir("ide"))
            exclude("README.md")
        }
    }
}

val syncWeb = tasks.register<SyncWeb>("syncWeb") {
    source.set(rootProject.layout.projectDirectory.dir("../web"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncWeb, SyncWeb::output)
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    // The native sections (Artifacts, Files, Devbox) and the viewer: Compose, Material 3.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.core:core-ktx:1.15.0")
    // Images (and thumbnails): Coil, with SVG; zoomable, subsampled images: Telephoto.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("me.saket.telephoto:zoomable-image-coil:0.14.0")
    // Audio and video.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    // Markdown.
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:image:4.6.2")
    // The assistant's voice call (VoiceCall): WebRTC, as built for Android by the webrtc-sdk project.
    implementation("io.github.webrtc-sdk:android:144.7559.15")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
