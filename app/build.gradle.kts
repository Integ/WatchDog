plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.watchdog.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.watchdog.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    val releaseStoreFile = System.getenv("SIGNING_STORE_FILE")
    val releaseStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    val releaseStoreType = when {
        releaseStoreFile.isNullOrBlank() -> null
        releaseStoreFile.endsWith(".p12", ignoreCase = true) -> "PKCS12"
        releaseStoreFile.endsWith(".pfx", ignoreCase = true) -> "PKCS12"
        else -> null
    }
    val hasReleaseSigning = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword
    ).all { !it.isNullOrBlank() }
    val isCi = System.getenv("CI") == "true"

    if (isCi && !hasReleaseSigning) {
        throw GradleException(
            "Missing release signing env vars. " +
                "Set SIGNING_STORE_FILE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD."
        )
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile!!)
                if (releaseStoreType != null) {
                    storeType = releaseStoreType
                }
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
