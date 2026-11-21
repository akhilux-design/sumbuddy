import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

/** A gradle property as a Java string literal for a BuildConfig field (empty when it is not set). */
fun stringProperty(name: String): String {
    val value = providers.gradleProperty(name).orNull.orEmpty()
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.expenses.app"
    compileSdk = 37

    defaultConfig {
        applicationId = providers.gradleProperty("app.applicationId").get()
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.2"

        buildConfigField("String", "LINK_HOST", "\"${providers.gradleProperty("app.linkHost").get()}\"")
        // The host of the App Link intent filter for friends' pay links (AndroidManifest.xml, docs/server.md 9.8).
        manifestPlaceholders["linkHost"] = providers.gradleProperty("app.linkHost").get()
        // Server, sign-in and push settings (docs/server.md 9.8). Placeholders come from gradle.properties.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", stringProperty("app.googleWebClientId"))
        buildConfigField("String", "FCM_PROJECT_ID", stringProperty("app.fcm.projectId"))
        buildConfigField("String", "FCM_APPLICATION_ID", stringProperty("app.fcm.applicationId"))
        buildConfigField("String", "FCM_API_KEY", stringProperty("app.fcm.apiKey"))
        buildConfigField("String", "FCM_SENDER_ID", stringProperty("app.fcm.senderId"))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // No dependency metadata block in the APK or bundle: it is encrypted with a Google key, so nobody else can read it,
    // and F-Droid refuses it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Release signing. The keystore and its password live only on the maintainer's machine (never in this repository):
    // the gradle property `app.release.keystoreProperties` names a properties file (storeFile, storePassword, keyAlias,
    // keyPassword), by default ~/.xpense-release/keystore.properties. Without it, `assembleRelease` makes an unsigned
    // APK, which is what anyone else building from source (and the open-source stores) gets.
    val releaseKeystore = File(
        providers.gradleProperty("app.release.keystoreProperties").orNull
            ?: "${System.getProperty("user.home")}/.xpense-release/keystore.properties",
    )
    val releaseSigning = if (releaseKeystore.isFile) {
        val keys = Properties().apply { releaseKeystore.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = File(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        debug {
            // Its own app id and version name, so a debug APK installs next to the released app and can never replace
            // it (both are signed with the same certificate). Debug builds sign in with Dev sign-in against the local
            // server, so they need no Google client or Firebase app for this id.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // The local server (`npm run dev` in server/) as the emulator sees the Mac. Cleartext is allowed for this
            // host only, and only in debug builds (src/debug/res/xml/network_security_config.xml).
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8787\"")
            // Debug builds offer "Dev sign-in" against the local server. Release builds contain none of its code.
            buildConfigField("boolean", "DEV_SIGN_IN", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", stringProperty("app.apiBaseUrl"))
            buildConfigField("boolean", "DEV_SIGN_IN", "false")
            signingConfig = releaseSigning
        }
    }
}

room {
    // The database schema of every version is kept in app/schemas so later migrations can be written and tested.
    schemaDirectory("$projectDir/schemas")
}

// A release build must not ship a placeholder: a link built with a host nobody here owns would send a friend's token
// to a stranger, and a placeholder API or client id would make sign-in fail for everyone (docs/server.md 9.8).
val checkReleaseConfig = tasks.register("checkReleaseConfig") {
    val apiBaseUrl = providers.gradleProperty("app.apiBaseUrl").orElse("")
    val googleWebClientId = providers.gradleProperty("app.googleWebClientId").orElse("")
    val linkHost = providers.gradleProperty("app.linkHost").orElse("")
    doLast {
        val problems = buildList {
            val api = apiBaseUrl.get()
            if (api.isBlank() || api.contains("example.workers.dev") || !api.startsWith("https://")) {
                add("app.apiBaseUrl is a placeholder or not https")
            }
            if (googleWebClientId.get().isBlank() || googleWebClientId.get().startsWith("REPLACE_ME")) {
                add("app.googleWebClientId is not set")
            }
            if (linkHost.get().isBlank() || linkHost.get() == "xpense.app") add("app.linkHost is the placeholder xpense.app")
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Release build stopped: " + problems.joinToString("; ") + ". See docs/server-setup.md.")
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(checkReleaseConfig)
}

// The Room migration test builds old databases from the exported schemas (Robolectric does not see test assets).
tasks.withType<Test>().configureEach {
    val schemas = layout.projectDirectory.dir("schemas")
    systemProperty("room.schemas", schemas.asFile.absolutePath)
    // LiveServerTest runs only with -PliveApi=http://127.0.0.1:8787 (the local server from `npm run dev`).
    systemProperty("live.api", providers.gradleProperty("liveApi").getOrElse(""))
    inputs.files(fileTree(schemas)).withPropertyName("roomSchemas").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":parser"))
    implementation(project(":splits"))
    implementation(project(":splitwise"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    // Splitting: the API client, Google sign-in through Credential Manager, and push without google-services.json.
    implementation(libs.okhttp)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
