import java.net.URI
import java.nio.file.Files

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Gradle does not expose local.properties as project properties, so it is
// read explicitly here (local.properties is untracked -> per-machine settings).
fun localProp(key: String): String? {
    val f = rootProject.layout.projectDirectory.file("local.properties").asFile
    if (!f.isFile) return null
    return f.readLines()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')?.trim()
}

// Opt-in: if the checkout lives in a synced folder (OneDrive etc.) that locks build
// intermediates, put e.g.  build.dir=C:/some/local/path/app  in local.properties
// (or pass -Pbuild.dir=...). Default is the normal app/build.
val customBuildDir: String? = (project.findProperty("build.dir") as String?) ?: localProp("build.dir")
if (!customBuildDir.isNullOrBlank()) {
    layout.buildDirectory.set(file(customBuildDir))
}

// ---- Backend origin (BuildConfig.API_BASE_URL) ---------------------------------------------------
// An ORIGIN, scheme://host[:port]/ , with NO "/api" (the endpoints already carry "api/"; passing
// ".../api" would double-prefix every request). The REST client and the Socket.IO client both use it.
// Most specific wins, per build type:
//   1. -P API_BASE_URL_DEBUG / -P API_BASE_URL_RELEASE      on the Gradle command line
//   2. API_BASE_URL_DEBUG / API_BASE_URL_RELEASE            in local.properties (untracked, this machine only)
//   3. -P API_BASE_URL, then API_BASE_URL in local.properties   (applies to BOTH build types)
//   4. built-in default: debug = emulator alias http://10.0.2.2:3000/ (host loopback, server PORT=3000);
//      release = a non-routable placeholder, and assembleRelease/bundleRelease FAIL until a real
//      https URL is provided (cleartext is blocked in release by src/main network_security_config).
// Production example (the URL Render assigns to your web service; never commit a guessed value):
//   local.properties:   API_BASE_URL_RELEASE=https://<your-service>.onrender.com/
//   or CLI:             ./gradlew :app:assembleRelease -PAPI_BASE_URL_RELEASE=https://<your-service>.onrender.com/
fun apiProp(key: String): String? =
    ((project.findProperty(key) as String?) ?: localProp(key))?.trim()?.takeIf { it.isNotEmpty() }

fun apiBaseUrlFor(buildType: String): String? = apiProp("API_BASE_URL_${buildType.uppercase()}") ?: apiProp("API_BASE_URL")

/** Null when [url] is a usable origin; otherwise what is wrong with it. */
fun originProblem(url: String, httpsOnly: Boolean): String? {
    if ('"' in url || '\\' in url) return "must not contain quotes or backslashes"
    val uri = runCatching { URI(url) }.getOrNull() ?: return "is not a valid URL"
    val schemeOk = uri.scheme == "https" || (!httpsOnly && uri.scheme == "http")
    if (!schemeOk) return if (httpsOnly) "must use https://" else "must start with http:// or https://"
    if (uri.host.isNullOrBlank()) return "must include a host"
    if (!(uri.path.isNullOrEmpty() || uri.path == "/") || uri.query != null || uri.fragment != null) {
        return "must be the server root only, without /api or any path, query or fragment"
    }
    return null
}

val debugApiBaseUrl: String = (apiBaseUrlFor("debug") ?: "http://10.0.2.2:3000/").also { url ->
    // The URL is embedded in generated source, so quotes are refused outright; other problems only warn for debug.
    require('"' !in url && '\\' !in url) { "API_BASE_URL(_DEBUG) must not contain quotes or backslashes" }
    originProblem(url, httpsOnly = false)?.let { logger.warn("API_BASE_URL for the debug build $it: $url") }
}
val releaseApiBaseUrlOverride: String? = apiBaseUrlFor("release")?.also { url ->
    require('"' !in url && '\\' !in url) { "API_BASE_URL(_RELEASE) must not contain quotes or backslashes" }
}
val releaseApiBaseUrl: String = releaseApiBaseUrlOverride ?: "https://configure-api-base-url.invalid/"

// Fail fast instead of shipping a release build that talks to a placeholder.
gradle.taskGraph.whenReady {
    val packagesRelease = allTasks.any { it.project == project && (it.name == "assembleRelease" || it.name == "bundleRelease") }
    if (packagesRelease) {
        val url = releaseApiBaseUrlOverride ?: throw GradleException(
            "Release builds need the production backend URL. Set API_BASE_URL_RELEASE=https://<your-service>.onrender.com/ " +
                "in local.properties, or pass -PAPI_BASE_URL_RELEASE=... (the server root, without /api)."
        )
        originProblem(url, httpsOnly = true)?.let {
            throw GradleException("API_BASE_URL_RELEASE $it. Got: $url")
        }
    }
}

android {
    namespace = "com.example.eventmanagementhub"
    // API 36 minor release 1 ("Android 16.0 (36.1)" in SDK Manager). Plain
    // platform 36 is not required. targetSdk stays 36.
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.example.eventmanagementhub"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    // Backend origin baked into BuildConfig.API_BASE_URL: see the "Backend origin" block above `android`.
    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        buildConfig = true
    }
}

// Windows: Gradle/AGP delete a task's previous outputs before re-running it, and
// java.nio delete() throws AccessDeniedException for ANY child carrying the
// FILE_ATTRIBUTE_READONLY flag — settable by Explorer, antivirus or a prior copy —
// even when nothing holds a file handle. Two concrete failures so far:
// Only active when build.dir is customised (see above).
//   - RemovePreviousOutputsStep -> outputs/apk/debug (packageDebug)
//   - DexArchiveBuilderTaskDelegate.cleanOutputDir -> intermediates/desugar_graph/**/
//     dirs_bucket_* (dexBuilderDebug), also seen on intermediates/project_dex_archive
// The flag appears anywhere under the build dir, so targeting single paths keeps
// chasing it; clear it across the WHOLE tree at task-graph-ready time, which runs
// after the graph is known but BEFORE the first task action executes.
//
// The stale workspace under intermediates/incremental/packageDebug/tmp is also
// removed for an older failure mode (zip-cache handles left by a dead JVM).
if (!customBuildDir.isNullOrBlank()) gradle.taskGraph.whenReady {
    try {
        layout.buildDirectory.get().asFile.walkTopDown().forEach { f ->
            try {
                // File.setWritable(true) silently FAILS on this tree (returns false,
                // flag unchanged — verified on JDK 21 / JBR), which is why the old
                // hook never cleared anything. NIO dos:readonly actually works.
                Files.setAttribute(f.toPath(), "dos:readonly", false)
            } catch (_: Exception) {
                // Not a DOS attribute or unreadable entry: skip this one.
            }
        }
    } catch (_: Exception) {
        // Best-effort: if the walk itself fails, stale dirs below still get a try.
    }
    val staleDirs = listOf(
        layout.buildDirectory.dir("outputs/apk/debug").get().asFile,
        layout.buildDirectory.dir("intermediates/incremental/packageDebug/tmp").get().asFile
    )
    for (dir in staleDirs) {
        if (!dir.exists()) continue
        try {
            dir.deleteRecursively()
        } catch (_: Exception) {
            // See above: best-effort.
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.socketio)
    implementation(libs.androidx.datastore.preferences)

    // Outdoor map layer: OpenStreetMap tiles via osmdroid (no API key, no
    // Google Play Services, no billing). Indoor navigation uses the backend
    // graph + Compose canvas and does not depend on this.
    implementation(libs.osmdroid.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.retrofit)
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}