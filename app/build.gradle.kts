plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.lettuceketchup.tvqs"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.lettuceketchup.tvqs"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // ponytail: debug key, fine for sideloading on one TV; a real keystore if this is ever published.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Sideloaded, not on Play: targetSdk 30 matches the TV's Android 11.
    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
