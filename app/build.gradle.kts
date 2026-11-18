plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phoneassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phoneassistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    // One committed, self-signed key for every build, so the signature never changes
    // and updates install over the top instead of demanding an uninstall first.
    // This key is public: it is fine for sideloading, never for Play Store distribution.
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("signing/phone-assistant.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
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
}

// Deliberately no runtime dependencies: the overlay, chat UI and HTTP client all
// use platform APIs so the app stays small and builds without a dependency graph.
dependencies {
}
