plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nz.luke.foldwallpaper"
    compileSdk = 35

    defaultConfig {
        applicationId = "nz.luke.foldwallpaper"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Fixed key so every new build installs over the old one.
    signingConfigs {
        create("shared") {
            storeFile = file("../keystore/release.jks")
            storePassword = "foldwall"
            keyAlias = "foldwall"
            keyPassword = "foldwall"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
