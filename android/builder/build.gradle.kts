plugins {
    id("com.android.application")
}

val archpheneAbi = providers.gradleProperty("archpheneAbi").orNull
val sourceValidation =
    providers.gradleProperty("archpheneSourceValidation")
        .map(String::toBooleanStrict)
        .orElse(false)
require(archpheneAbi == null || archpheneAbi in setOf("x86_64", "arm64-v8a")) {
    "archpheneAbi must be x86_64 or arm64-v8a"
}

android {
    namespace = "org.archphene.builder"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "org.archphene.builder"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        if (archpheneAbi != null) {
            ndk {
                abiFilters += archpheneAbi
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("build/generated/jniLibs")
            jniLibs.directories.add("build/generated/builderRuntime/jniLibs")
            assets.directories.add("build/generated/builderRuntime/assets")
        }
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        jniLibs {
            // The verified glibc loader is executed as a child process.
            useLegacyPackaging = true
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles("proguard-rules.pro")
        }
    }
}

tasks.named("preBuild").configure {
    if (!sourceValidation.get()) {
        dependsOn(rootProject.tasks.named("buildArchpheneRust"))
        dependsOn(rootProject.tasks.named("stageArchpheneBuilderRuntime"))
    }
}
