plugins {
    id("com.android.application")
}

val archpheneAbi = providers.gradleProperty("archpheneAbi").orNull
require(archpheneAbi == null || archpheneAbi in setOf("x86_64", "arm64-v8a")) {
    "archpheneAbi must be x86_64 or arm64-v8a"
}

dependencies {
    // AGP already resolves this checksum-pinned artifact for its own signing
    // work. Use the valid library jar rather than Build Tools 36's malformed
    // command-line apksigner bundle, whose manifest CRC breaks R8 transforms.
    implementation("com.android.tools.build:apksig:9.3.0")
    testImplementation("junit:junit:4.13.2")
}

android {
    namespace = "org.archphene.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "org.archphene.app"
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
            jniLibs.directories.add("build/generated/compositorJniLibs")
            jniLibs.directories.add("build/generated/packageRuntime/jniLibs")
            assets.directories.add("build/generated/packageRuntime/assets")
            assets.directories.add("build/generated/launcherTemplate/assets")
        }
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        jniLibs {
            // Package tools are executed as child processes through the bundled
            // glibc loader, so they require real filesystem paths rather than
            // mmap-only entries inside the APK.
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
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(rootProject.tasks.named("buildArchpheneRust"))
    dependsOn(rootProject.tasks.named("buildArchpheneCompositor"))
    dependsOn(rootProject.tasks.named("stageArchphenePackageRuntime"))
    dependsOn(rootProject.tasks.named("stageArchpheneLauncherTemplate"))
}
