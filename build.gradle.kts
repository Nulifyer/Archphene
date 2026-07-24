plugins {
    id("com.android.application") version "9.3.0" apply false
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
    outputs.dir("android/app/build/generated/jniLibs")
}

tasks.register<Exec>("stageArchphenePackageRuntime") {
    workingDir(rootDir)
    commandLine("bash", "scripts/stage-archphene-package-runtime.sh")
    inputs.files(
        file("scripts/stage-archphene-package-runtime.sh"),
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
