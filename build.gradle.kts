plugins {
    id("com.android.application") version "9.3.0" apply false
}

val requiredJdkFeature = 26
check(Runtime.version().feature() == requiredJdkFeature) {
    "Archphene builds require JDK $requiredJdkFeature; current runtime is ${System.getProperty("java.version")}"
}

val sourceValidation =
    providers.gradleProperty("archpheneSourceValidation")
        .map(String::toBooleanStrict)
        .orElse(false)
if (sourceValidation.get()) {
    val allowedTasks = setOf("lintDebug", "testDebugUnitTest")
    check(
        gradle.startParameter.taskNames.isNotEmpty() &&
            gradle.startParameter.taskNames.all { task ->
                task.substringAfterLast(':') in allowedTasks
            },
    ) {
        "archpheneSourceValidation may run only lintDebug and testDebugUnitTest"
    }
}

tasks.register<Exec>("buildArchpheneRust") {
    workingDir(rootDir)
    commandLine("bash", "scripts/build-archphene-rust.sh")
    inputs.files(
        fileTree("crates") {
            include("**/*.rs", "**/Cargo.toml")
        },
        file("Cargo.lock"),
        file("Cargo.toml"),
    )
    outputs.dirs(
        "android/app/build/generated/jniLibs",
        "android/builder/build/generated/jniLibs",
    )
}

tasks.register<Exec>("buildArchpheneCompositor") {
    workingDir(rootDir)
    commandLine("bash", "scripts/build-archphene-compositor.sh")
    inputs.files(
        fileTree("native/archphene-compositor") {
            include("src/**", "Cargo.toml", ".cargo/config.toml")
        },
        file("Cargo.lock"),
        file("Cargo.toml"),
        file("scripts/build-archphene-compositor.sh"),
    )
    outputs.dir("android/app/build/generated/compositorJniLibs")
}

val buildArchpheneAndroidDbus =
    tasks.register<Exec>("buildArchpheneAndroidDbus") {
        workingDir(rootDir)
        commandLine("bash", "scripts/build-android-dbus-all-podman.sh")
        inputs.files(
            fileTree("native/archphene-android-capability") {
                include("**/*.c", "**/*.h")
            },
            fileTree("native/archphene-portal") {
                include("**/*.c", "**/*.h")
            },
            fileTree("native/archphene-dbus") {
                include("patches/*.patch")
            },
            file("scripts/build-android-dbus.sh"),
            file("scripts/build-android-dbus-podman.sh"),
            file("scripts/build-android-dbus-all-podman.sh"),
            file("containers/android-native.Containerfile"),
        )
        outputs.files(
            "tooling/build/android-dbus/x86_64/dbus-daemon",
            "tooling/build/android-dbus/x86_64/portal-service",
            "tooling/build/android-dbus/x86_64/portal-probe",
            "tooling/build/android-dbus/x86_64/xdg-open",
            "tooling/build/android-dbus/aarch64/dbus-daemon",
            "tooling/build/android-dbus/aarch64/portal-service",
            "tooling/build/android-dbus/aarch64/portal-probe",
            "tooling/build/android-dbus/aarch64/xdg-open",
        )
    }

tasks.register<Sync>("stageArchpheneAndroidDbus") {
    dependsOn(buildArchpheneAndroidDbus)
    from("tooling/build/android-dbus/x86_64") {
        include("dbus-daemon", "portal-service", "portal-probe")
        into("x86_64")
        rename("dbus-daemon", "libarchphene_dbus_daemon.so")
        rename("portal-service", "libarchphene_portal_service.so")
        rename("portal-probe", "libarchphene_portal_probe.so")
    }
    from("tooling/build/android-dbus/aarch64") {
        include("dbus-daemon", "portal-service", "portal-probe")
        into("arm64-v8a")
        rename("dbus-daemon", "libarchphene_dbus_daemon.so")
        rename("portal-service", "libarchphene_portal_service.so")
        rename("portal-probe", "libarchphene_portal_probe.so")
    }
    into("android/app/build/generated/portalJniLibs")
}

val buildArchpheneAndroidGpu =
    tasks.register<Exec>("buildArchpheneAndroidGpu") {
        workingDir(rootDir)
        commandLine("bash", "scripts/build-android-gpu-all-podman.sh")
        inputs.files(
            fileTree("native/android-gpu-helper") {
                include("patches/*.patch")
            },
            file("scripts/build-android-gpu-helper.sh"),
            file("scripts/build-android-gpu-helper-podman.sh"),
            file("scripts/build-android-gpu-all-podman.sh"),
            file("containers/android-native.Containerfile"),
        )
        outputs.files(
            "tooling/build/android-gpu/x86_64/virgl_test_server_android",
            "tooling/build/android-gpu/aarch64/virgl_test_server_android",
        )
    }

tasks.register<Sync>("stageArchpheneAndroidGpu") {
    dependsOn(buildArchpheneAndroidGpu)
    from("tooling/build/android-gpu/x86_64/virgl_test_server_android") {
        into("x86_64")
        rename { "libarchphene_virgl_server.so" }
    }
    from("tooling/build/android-gpu/aarch64/virgl_test_server_android") {
        into("arm64-v8a")
        rename { "libarchphene_virgl_server.so" }
    }
    into("android/app/build/generated/gpuJniLibs")
}

val buildArchpheneAndroidAudioX86 =
    tasks.register<Exec>("buildArchpheneAndroidAudioX86") {
        workingDir(rootDir)
        commandLine(
            "bash",
            "scripts/build-android-pulse-podman.sh",
            "--architecture",
            "x86_64",
        )
        inputs.files(
            file("native/archphene-audio/termux-pulse-packages.tsv"),
            file("scripts/build-android-pulse.sh"),
            file("scripts/build-android-pulse-podman.sh"),
            file("containers/android-native.Containerfile"),
        )
        outputs.dir("tooling/build/android-pulse/x86_64/out")
    }

val buildArchpheneAndroidAudioArm =
    tasks.register<Exec>("buildArchpheneAndroidAudioArm") {
        workingDir(rootDir)
        commandLine(
            "bash",
            "scripts/build-android-pulse-podman.sh",
            "--architecture",
            "aarch64",
        )
        inputs.files(
            file("native/archphene-audio/termux-pulse-packages.tsv"),
            file("scripts/build-android-pulse.sh"),
            file("scripts/build-android-pulse-podman.sh"),
            file("containers/android-native.Containerfile"),
        )
        outputs.dir("tooling/build/android-pulse/aarch64/out")
    }

tasks.register<Exec>("stageArchpheneAndroidAudio") {
    dependsOn(buildArchpheneAndroidAudioX86, buildArchpheneAndroidAudioArm)
    workingDir(rootDir)
    commandLine("bash", "scripts/stage-archphene-android-audio.sh")
    inputs.files(
        fileTree("tooling/build/android-pulse/x86_64/out"),
        fileTree("tooling/build/android-pulse/aarch64/out"),
        file("scripts/stage-archphene-android-audio.sh"),
    )
    outputs.dir("android/app/build/generated/audioJniLibs")
}

val verifyArchpheneTerminalFont =
    tasks.register<Exec>("verifyArchpheneTerminalFont") {
        workingDir("third_party/jetbrains-mono-nerd-font")
        commandLine("sha256sum", "--check", "--quiet", "SHA256SUMS")
        inputs.files(
            "third_party/jetbrains-mono-nerd-font/JetBrainsMonoNLNerdFontMono-Regular.ttf",
            "third_party/jetbrains-mono-nerd-font/OFL.txt",
            "third_party/jetbrains-mono-nerd-font/SHA256SUMS",
        )
    }

tasks.register<Sync>("stageArchpheneTerminalFont") {
    dependsOn(verifyArchpheneTerminalFont)
    from("third_party/jetbrains-mono-nerd-font/JetBrainsMonoNLNerdFontMono-Regular.ttf")
    from("third_party/jetbrains-mono-nerd-font/OFL.txt") {
        into("licenses")
        rename { "JetBrainsMonoNerdFont-OFL.txt" }
    }
    into("android/app/build/generated/terminalFont/assets")
}

val rebuildArchphenePackageRuntimePathBridges =
    tasks.register<Exec>("rebuildArchphenePackageRuntimePathBridges") {
        workingDir(rootDir)
        commandLine("bash", "scripts/rebuild-package-runtime-path-bridges.sh")
        inputs.files(
            file("native/archphene-glibc-path-bridge/path_bridge.c"),
            file("native/archphene-glibc-path-bridge/arm64.map"),
            file("scripts/rebuild-package-runtime-path-bridges.sh"),
            file("scripts/lib/common.sh"),
        )
        outputs.files(
            "tooling/build/ci-package-runtime/tooling/build/" +
                "archphene-path-bridge-x86_64/libarchphene_path_bridge.so",
            "tooling/build/ci-package-runtime/SHA256SUMS",
            "tooling/build/ci-package-runtime-arm64/tooling/build/" +
                "archphene-path-bridge-aarch64/libarchphene_path_bridge.so",
            "tooling/build/ci-package-runtime-arm64/SHA256SUMS",
        )
    }

tasks.register<Exec>("stageArchphenePackageRuntime") {
    dependsOn(rebuildArchphenePackageRuntimePathBridges)
    dependsOn(buildArchpheneAndroidDbus)
    workingDir(rootDir)
    commandLine("bash", "scripts/stage-archphene-package-runtime.sh")
    inputs.files(
        file("scripts/stage-archphene-package-runtime.sh"),
        file("prebuilt/gtk3-compat/SHA256SUMS"),
        file("prebuilt/gtk3-compat/manifest.json"),
        file("prebuilt/qt-bridge/SHA256SUMS"),
        file("prebuilt/qt-bridge/manifest.json"),
        file("prebuilt/qt-bridge/manifest-arm64-v8a.json"),
        fileTree("prebuilt/gtk3-compat") {
            include("x86_64/*.so", "aarch64/*.so")
        },
        fileTree("prebuilt/qt-bridge") {
            include("x86_64/libarchphene_qt_platform_theme.so")
            include("x86_64/libarchphene_qt_style.so")
            include("x86_64/libarchphene_kde_config.so")
            include("arm64-v8a/*.so")
        },
        fileTree("tooling/build/ci-package-runtime") {
            include("SHA256SUMS", "**/runtime-root/usr/bin/*", "**/runtime-root/usr/lib/*")
            include("**/elf-needed-resolved.tsv", "**/glibc-archphene-runtime-x86_64/*")
        },
        fileTree("tooling/build/ci-package-runtime-arm64") {
            include("SHA256SUMS", "**/runtime-root/usr/bin/*", "**/runtime-root/usr/lib/*")
            include("**/elf-needed-resolved.tsv", "**/glibc-archphene-runtime-aarch64/*")
        },
        file("tooling/build/android-dbus/x86_64/xdg-open"),
        file("tooling/build/android-dbus/aarch64/xdg-open"),
    )
    outputs.dir("android/app/build/generated/packageRuntime")
}

tasks.register<Exec>("stageArchpheneBuilderRuntime") {
    dependsOn(rebuildArchphenePackageRuntimePathBridges)
    workingDir(rootDir)
    commandLine("bash", "scripts/stage-archphene-builder-runtime.sh")
    inputs.files(
        file("scripts/stage-archphene-builder-runtime.sh"),
        fileTree("tooling/build/ci-package-runtime") {
            include("SHA256SUMS", "**/glibc-archphene-runtime-x86_64/*")
            include("**/archphene-path-bridge-x86_64/*")
        },
        fileTree("tooling/build/ci-package-runtime-arm64") {
            include("SHA256SUMS", "**/glibc-archphene-runtime-aarch64/*")
            include("**/archphene-path-bridge-aarch64/*")
        },
    )
    outputs.dir("android/builder/build/generated/builderRuntime")
}

tasks.register<Sync>("stageArchpheneLauncherTemplate") {
    dependsOn(":android:launcher-template:assembleRelease")
    from("android/launcher-template/build/outputs/apk/release/launcher-template-release-unsigned.apk")
    into("android/app/build/generated/launcherTemplate/assets/launcher")
    rename { "launcher-template.apk" }
}
