plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Release signing is read from environment variables (set by CI from GitHub secrets).
val releaseKeystore: String? = System.getenv("NOVA_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.nova.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nova.iptv"
        minSdk = 24
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // Viewer / account sync (filled from repository secrets in CI; empty = feature off)
        fun cfg(name: String) = (System.getenv(name) ?: "").filter { it.isLetterOrDigit() || it in ":/._-" }
        buildConfigField("String", "FIREBASE_DB_URL", "\"${cfg("FIREBASE_DB_URL")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${cfg("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "GOOGLE_TV_CLIENT_ID", "\"${cfg("GOOGLE_TV_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_TV_CLIENT_SECRET", "\"${cfg("GOOGLE_TV_CLIENT_SECRET")}\"")
        buildConfigField("String", "YOUTUBE_API_KEY", "\"${cfg("YOUTUBE_API_KEY")}\"")
    }

    signingConfigs {
        // Stable key committed to the repo so every CI build can be installed as an update
        // over the previous one. For public/Play distribution, provide your own key via secrets.
        create("nova") {
            storeFile = file("nova-dev.keystore")
            storePassword = "novadev2026"
            keyAlias = "nova"
            keyPassword = "novadev2026"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("NOVA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NOVA_KEY_ALIAS")
                keyPassword = System.getenv("NOVA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("nova")
        }
        release {
            // Non-debuggable, fully optimised by ART. R8 stays off until it is verified on devices.
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("nova")
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // DI
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Background work
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Storage
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.room:room-paging:2.6.1")
    implementation("androidx.paging:paging-runtime-ktx:3.3.5")
    implementation("androidx.paging:paging-compose:3.3.5")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.zxing:core:3.5.3")

    // Media
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    // FFmpeg audio decoders (AC-3, E-AC-3/Atmos, DTS, TrueHD…) for tracks the device can't decode itself.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // Images
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
