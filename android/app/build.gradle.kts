plugins {
    id("com.android.application")
}

val archpheneAbi = providers.gradleProperty("archpheneAbi").orNull
val archpheneVersionCode = providers.gradleProperty("archpheneVersionCode").orNull
val archpheneVersionName = providers.gradleProperty("archpheneVersionName").orNull
val debugApplicationIdSuffix =
    providers.gradleProperty("archpheneDebugApplicationIdSuffix").orNull ?: ".debug"
val sourceValidation =
    providers.gradleProperty("archpheneSourceValidation")
        .map(String::toBooleanStrict)
        .orElse(false)
require(archpheneAbi == null || archpheneAbi in setOf("x86_64", "arm64-v8a")) {
    "archpheneAbi must be x86_64 or arm64-v8a"
}
require(
    archpheneVersionCode == null ||
        archpheneVersionCode.matches(Regex("[1-9][0-9]{0,9}")) &&
        archpheneVersionCode.toLong() <= 2_100_000_000L,
) {
    "archpheneVersionCode must be a positive Android version code"
}
require(
    archpheneVersionName == null ||
        archpheneVersionName.length <= 64 &&
        archpheneVersionName.matches(
            Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?"),
        ),
) {
    "archpheneVersionName must use MAJOR.MINOR.PATCH syntax"
}
require(Regex("""\.debug(?:\.[a-z][a-z0-9_]{0,31})?""").matches(debugApplicationIdSuffix)) {
    "archpheneDebugApplicationIdSuffix must be .debug or .debug.<test-instance>"
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
        versionCode = archpheneVersionCode?.toInt() ?: 1
        versionName = archpheneVersionName ?: "0.1.0"
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
            jniLibs.directories.add("build/generated/portalJniLibs")
            jniLibs.directories.add("build/generated/gpuJniLibs")
            jniLibs.directories.add("build/generated/audioJniLibs")
            jniLibs.directories.add("build/generated/cameraJniLibs")
            assets.directories.add("build/generated/packageRuntime/assets")
            assets.directories.add("build/generated/launcherTemplate/assets")
            assets.directories.add("build/generated/terminalFont/assets")
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
            applicationIdSuffix = debugApplicationIdSuffix
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
    if (!sourceValidation.get()) {
        dependsOn(rootProject.tasks.named("buildArchpheneRust"))
        dependsOn(rootProject.tasks.named("buildArchpheneCompositor"))
        dependsOn(rootProject.tasks.named("stageArchphenePackageRuntime"))
        dependsOn(rootProject.tasks.named("stageArchpheneAndroidDbus"))
        dependsOn(rootProject.tasks.named("stageArchpheneAndroidGpu"))
        dependsOn(rootProject.tasks.named("stageArchpheneAndroidAudio"))
        dependsOn(rootProject.tasks.named("stageArchphenePipeWireCamera"))
        dependsOn(rootProject.tasks.named("stageArchpheneLauncherTemplate"))
        dependsOn(rootProject.tasks.named("stageArchpheneTerminalFont"))
    }
}
