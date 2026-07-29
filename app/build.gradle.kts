plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.depthwp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.depthwp"
        minSdk = 26
        targetSdk = 36
        // Die CI reicht bei jedem Lauf eine hoehere Nummer herein, damit jedes Update das
        // installierte ueberholt. Lokal gebaut bleibt es bei diesem Standardwert.
        versionCode = (System.getenv("VERSION_CODE") ?: "2").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /*
     * Android refuses to install an update that was signed with a different key than the copy
     * already on the phone — it would demand an uninstall, taking every saved wallpaper with it.
     * A CI runner generates a fresh throwaway debug key on every run, so the build server is handed
     * the very same debug keystore this machine uses, passed in through KEYSTORE_PATH.
     * Without that variable (a normal local build) nothing changes and Gradle signs as before.
     */
    val ciKeystore = System.getenv("KEYSTORE_PATH")
    if (ciKeystore != null) {
        signingConfigs {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            if (ciKeystore != null) signingConfig = signingConfigs.getByName("ci")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
