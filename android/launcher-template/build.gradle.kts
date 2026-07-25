plugins {
    id("com.android.application")
}

android {
    namespace = "org.archphene.launcher"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "org.archphene.linux.p00000000000000000000000000000000"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = false
        }
        getByName("release") {
            // The template identity is a function of launcher inputs, not the
            // parent repository commit or dirty state. Otherwise every
            // unrelated manager change forces user-confirmed launcher updates.
            vcsInfo {
                include = false
            }
            isMinifyEnabled = true
            // LauncherApkAssembler replaces the stable package-owned icon
            // entry after resource linking. Keep resource paths stable so the
            // generated wrapper never has to patch resources.arsc.
            isShrinkResources = false
            proguardFiles("proguard-rules.pro")
        }
    }
}
