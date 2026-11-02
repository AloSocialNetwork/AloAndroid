import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun String.toBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val aloPublicDomain = providers.gradleProperty("aloPublicDomain").orElse("xn--80awj.online").get().trim()
val aloBaseUrl = providers.gradleProperty("aloBaseUrl").orElse("https://api.$aloPublicDomain/").get()
val aloAppLinkBaseUrl = providers.gradleProperty("aloAppLinkBaseUrl").orElse("https://$aloPublicDomain/").get()
val aloAppLinkHost = providers.gradleProperty("aloAppLinkHost").orElse(aloPublicDomain).get()
// A debug build talks to this server; empty means the Mac's LAN address (AloServerConfiguration.debugLocalDevelopmentHost).
// gradle.properties points it at the Alo test VPS; `-PaloDebugBaseUrl=` (empty) brings the local server back.
val aloDebugBaseUrl = providers.gradleProperty("aloDebugBaseUrl").orElse("").get().trim()
// This machine's values that stay out of git (Firebase): alo-local.properties at the project root, see the .example file.
val aloLocalProperties = Properties().apply {
    val file = rootProject.file("alo-local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun aloSetting(name: String): String =
    providers.gradleProperty(name).orNull ?: aloLocalProperties.getProperty(name).orEmpty()
val firebaseApplicationId = aloSetting("firebaseApplicationId")
val firebaseApiKey = aloSetting("firebaseApiKey")
val firebaseProjectId = aloSetting("firebaseProjectId")
val firebaseSenderId = aloSetting("firebaseSenderId")
val aloMapStyleUrl = providers.gradleProperty("aloMapStyleUrl")
    .orElse("https://tiles.openfreemap.org/styles/liberty")
    .get()
    .trim()

android {
    namespace = "com.alo.android"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.alo.android"
        minSdk = 24
        targetSdk = 36
        // Raise both for every release (scripts/android-release.sh). The properties are for testing an update on the stand.
        versionCode = providers.gradleProperty("aloVersionCode").orNull?.toInt() ?: 8
        versionName = providers.gradleProperty("aloVersionName").orNull ?: "0.1.7"

        ndk {
            // 64-bit phones and the Apple Silicon AVD use arm64-v8a; armeabi-v7a is for 32-bit phones (often Android 7–9,
            // or a 64-bit CPU with a 32-bit system). One APK holds both, so the updater and latest.apk stay one file.
            // x86/x86_64 stay out: no phone needs them, and every ABI adds ~40 MB of WebRTC, MapLibre and libsignal.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ALO_BASE_URL", aloBaseUrl.toBuildConfigString())
        buildConfigField("String", "ALO_APP_LINK_BASE_URL", aloAppLinkBaseUrl.toBuildConfigString())
        buildConfigField("String", "ALO_APP_LINK_HOST", aloAppLinkHost.toBuildConfigString())
        buildConfigField("String", "FIREBASE_APPLICATION_ID", firebaseApplicationId.toBuildConfigString())
        buildConfigField("String", "FIREBASE_API_KEY", firebaseApiKey.toBuildConfigString())
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebaseProjectId.toBuildConfigString())
        buildConfigField("String", "FIREBASE_SENDER_ID", firebaseSenderId.toBuildConfigString())
        buildConfigField("String", "ALO_MAP_STYLE_URL", aloMapStyleUrl.toBuildConfigString())
        // Test hooks: a throwaway server and session (AloDebugServer), design fixtures. Never in what people install.
        buildConfigField("boolean", "ALO_DEV_HOOKS", "false")
        manifestPlaceholders["aloUsesCleartextTraffic"] = "true"
        manifestPlaceholders["aloAppLinkHost"] = aloAppLinkHost
        // The website's sign-in QR (https://<host>/qr#…) opens the app on the site it came from.
        manifestPlaceholders["aloQrLinkHost"] = aloBaseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
            .ifBlank { aloAppLinkHost }
    }

    buildTypes {
        debug {
            buildConfigField("String", "ALO_BASE_URL", aloDebugBaseUrl.toBuildConfigString())
            buildConfigField("boolean", "ALO_DEV_HOOKS", "true")
            manifestPlaceholders["aloUsesCleartextTraffic"] = "true"
            // A debug build talks to the test VPS: its QR comes from there.
            manifestPlaceholders["aloQrLinkHost"] = aloDebugBaseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
                .ifBlank { aloAppLinkHost }
        }
        release {
            manifestPlaceholders["aloUsesCleartextTraffic"] = "false"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // The release build with the test hooks: what frame timings and the stand are measured on. A debug build is
        // several times slower (ART does not optimise debuggable code, Compose runs its debug paths), so it says nothing.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
            isProfileable = true
            buildConfigField("String", "ALO_BASE_URL", aloDebugBaseUrl.toBuildConfigString())
            buildConfigField("boolean", "ALO_DEV_HOOKS", "true")
            manifestPlaceholders["aloUsesCleartextTraffic"] = "true"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            excludes += "**/libsignal_jni_testing.so"
        }
        resources {
            excludes += setOf(
                "libsignal_jni*.dylib",
                "signal_jni*.dll",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)
    implementation(libs.maplibre.android)
    implementation(libs.webrtc.sdk.android)
    implementation(libs.livekit.android)
    implementation(libs.libsignal.client)
    implementation(libs.libsignal.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
