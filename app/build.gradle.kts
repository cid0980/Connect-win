plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cid0980.connectwin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cid0980.connectwin"
        minSdk = 30
        targetSdk = 34
        versionCode = 15
        versionName = "1.6.5"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("SIGNING_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("SIGNING_STORE_PASS") ?: "connectwin123"
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "connectwin"
                keyPassword = System.getenv("SIGNING_KEY_PASS") ?: "connectwin123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            val ksPath = System.getenv("SIGNING_KEYSTORE_PATH")
            signingConfig = if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // CRITICAL for Android 10–16: extracts libcloudflared.so to nativeLibraryDir
    // with executable SELinux context so ProcessBuilder can run it directly.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
