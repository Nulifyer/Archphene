plugins {
    id("com.android.application") version "9.3.0" apply false
}

val requiredJdk = "26.0.1"
check(System.getProperty("java.version") == requiredJdk) {
    "Archphene builds require JDK $requiredJdk; current runtime is ${System.getProperty("java.version")}"
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
