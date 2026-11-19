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
        versionCode = 91
        versionName = "0.91"
    }

    // One committed, self-signed key signs every build, debug and release alike, so the
    // signature never changes and updates install straight over the top. It is public:
    // ideal for a personal sideloaded app, and never acceptable for Play Store
    // distribution. See the README if you ever decide to share it.
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
