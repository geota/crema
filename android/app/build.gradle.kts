import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
    // AGP 9 has built-in Kotlin support — the `org.jetbrains.kotlin.android`
    // plugin is no longer applied; AGP applies Kotlin itself. The Compose and
    // serialization compiler plugins are still separate and must be applied.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Builds the de1-ffi Rust crate into per-ABI .so files and stages them in
    // build/rustJniLibs/android. See the `cargo` block below.
    id("net.mullvad.rust-android")
}

android {
    namespace = "coffee.crema"
    compileSdk = 36
    // Pinned to the NDK installed via the SDK Manager ("NDK (Side by side)").
    // AGP's built-in default NDK version differs, so without an explicit pin
    // Gradle looks for a version that isn't installed and fails with
    // "NDK is not installed". Update this if you install a different NDK.
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "coffee.crema"
        // Teclast P25T tablet runs Android 12 (API 31); also the phone floor.
        minSdk = 31
        // AGP 9 defaults targetSdk to compileSdk, but pin it explicitly so the
        // value is unambiguous and survives a compileSdk bump.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// AGP 9 removed the `kotlinOptions {}` block from `android {}`. The Kotlin JVM
// target now comes from the built-in Kotlin integration, which defaults
// `kotlin.compilerOptions.jvmTarget` to `android.compileOptions.targetCompatibility`
// (VERSION_17 above).
kotlin {
    compilerOptions {
        // The Nordic Kotlin-BLE-Library 2.0.0-alphaNN artifacts are compiled
        // with a pre-release Kotlin compiler. By default the Kotlin compiler
        // refuses pre-release-compiled classes ("was compiled by a pre-release
        // version of Kotlin and cannot be loaded"); this flag opts in to
        // accepting them. Tied to depending on a Nordic alpha — drop this when
        // Nordic ships a stable release built against a released Kotlin.
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // UniFFI's generated Kotlin depends on JNA for the FFI calls and on
    // kotlinx-coroutines (its runtime helpers reference it).
    // 5.17.0 is the first JNA whose Android libjnidispatch.so is built with
    // 16 KB ELF page alignment — required by Android 15+ / API 36+ devices and
    // emulators (16 KB memory pages). Older JNA fails to dlopen on them.
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The de1-app `CoreOutput` JSON is deserialized with kotlinx.serialization.
    // The generated `core/bindings/crema-core.kt` types are @Serializable.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Nordic Kotlin-BLE-Library (central role) — the BLE stack behind
    // `NordicBleTransport`. The hand-rolled `BluetoothGatt` layer was migrated
    // onto this in the `ble-nordic-migration` work.
    //
    // This is a `2.0.0-alphaNN` release: the API is NOT stable. The version is
    // pinned exactly; bump deliberately and re-verify `NordicBleTransport`
    // against the new alpha. `client-android` already pulls `client-core-*`,
    // `core`, `core-android`, and `environment-android` transitively, but the
    // three modules the app uses directly are declared explicitly for clarity.
    //   - core               : ConnectionState and other shared types
    //   - client-android     : CentralManager.native, Peripheral, scanning
    //   - environment-android: NativeAndroidEnvironment (must be close()d)
    // Server / advertiser modules are intentionally NOT included.
    val nordicBle = "2.0.0-alpha19"
    implementation("no.nordicsemi.kotlin.ble:core:$nordicBle")
    implementation("no.nordicsemi.kotlin.ble:client-android:$nordicBle")
    implementation("no.nordicsemi.kotlin.ble:environment-android:$nordicBle")
}

// ---------------------------------------------------------------------------
// Rust / NDK integration — net.mullvad.rust-android (Mullvad fork)
// ---------------------------------------------------------------------------
// `cargoBuild` compiles the `de1-ffi` cdylib for the listed Android ABIs and
// writes `libde1_ffi.so` into build/rustJniLibs/android/<abi>/. The wiring
// below feeds that directory into AGP's `merge*JniLibFolders` tasks so it
// ships in the APK. The NDK and the Rust target must be installed (see
// android/README.md).
cargo {
    // Path from this module to the Rust workspace member.
    module = "../../core/de1-ffi"
    libname = "de1_ffi"
    // de1-ffi is a Cargo *workspace member*, so `cargo` writes build output to
    // the workspace target dir (core/target), NOT <module>/target. The plugin
    // defaults to <module>/target to find the compiled .so to stage into the
    // APK; point it at the real workspace target or libde1_ffi.so never gets
    // packaged and the app crashes at load with UnsatisfiedLinkError.
    targetDirectory = "../../core/target"
    // Teclast P25T and the Pixel phone are both arm64. arm64-v8a is the only
    // ABI needed for real hardware; add "x86_64" here to also run on an emulator.
    targets = listOf("arm64")
    // "debug" keeps the build fast; switch to "release" for shippable APKs.
    profile = "debug"
    // The crate is part of a Cargo workspace; build just this package.
    extraCargoBuildArguments = listOf("--package", "de1-ffi")
}

// Depend AGP's per-variant `merge*JniLibFolders` tasks on `cargoBuild` and
// register the plugin's output dir as an input so the freshly built `.so` is
// packaged into the APK.
val rustJniLibsDir = layout.buildDirectory.dir("rustJniLibs/android").get()
tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }.configureEach {
    inputs.dir(rustJniLibsDir)
    dependsOn("cargoBuild")
}

// ---------------------------------------------------------------------------
// UniFFI Kotlin bindings — generated at build time from the de1-ffi cdylib
// ---------------------------------------------------------------------------
// `de1-ffi` ships a `uniffi-bindgen` binary. This task runs it against the
// arm64 cdylib `cargoBuild` produced (UniFFI reads the exported metadata from
// the compiled library) and emits the Kotlin bindings into an OutputDirectory.
//
// AGP 9 no longer accepts a `Provider` in `sourceSets { ... srcDir(...) }` for
// generated code — the Variant API's `addGeneratedSourceDirectory` is the
// supported path. It also auto-wires each variant's Kotlin compile to depend
// on this task, so no manual `dependsOn` on the compile is needed.
abstract class GenerateUniffiBindings : DefaultTask() {
    /// The de1-ffi crate source — re-run binding generation when it changes.
    @get:InputDirectory
    abstract val crateSource: DirectoryProperty

    /// The Cargo workspace root; `cargo run` executes from here.
    @get:Internal
    abstract val workspaceDir: DirectoryProperty

    /// Path to the `cargo` executable — set from `rust.cargoCommand` in
    /// `local.properties` so IDE-launched builds (no `~/.cargo/bin` on PATH)
    /// still resolve it; falls back to bare `cargo` for PATH-based CLI builds.
    @get:Internal
    abstract val cargoCommand: Property<String>

    /// The compiled cdylib `uniffi-bindgen` reads metadata from.
    @get:Internal
    abstract val library: RegularFileProperty

    /// Where the generated Kotlin lands.
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun generate() {
        val outDir = outputDirectory.get().asFile
        outDir.mkdirs()
        execOps.exec {
            workingDir = workspaceDir.get().asFile
            commandLine(
                cargoCommand.get(), "run", "--package", "de1-ffi", "--bin", "uniffi-bindgen", "--",
                "generate",
                // Skip uniffi-bindgen's ktlint auto-format step: ktlint is not
                // installed, and a build/-dir generated file needs no formatting.
                // Without this, the build prints a "ktlint: No such file" warning.
                "--no-format",
                "--library", library.get().asFile.absolutePath,
                "--language", "kotlin",
                "--out-dir", outDir.absolutePath,
            )
        }
    }
}

// The `net.mullvad.rust-android` plugin resolves `cargo` from `rust.cargoCommand`
// in local.properties (an absolute path, so IDE-launched builds without
// ~/.cargo/bin on PATH still work). The bindgen task below is a hand-written
// task, not part of the plugin, so it must read the same value itself.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val cargoExecutable: String = localProperties.getProperty("rust.cargoCommand") ?: "cargo"

val generateUniffiBindings =
    tasks.register<GenerateUniffiBindings>("generateUniffiBindings") {
        description = "Generate UniFFI Kotlin bindings for de1-ffi."
        group = "rust"
        cargoCommand.set(cargoExecutable)
        crateSource.set(layout.projectDirectory.dir("../../core/de1-ffi/src"))
        workspaceDir.set(layout.projectDirectory.dir("../../core"))
        library.set(
            layout.projectDirectory.file(
                "../../core/target/aarch64-linux-android/debug/libde1_ffi.so",
            ),
        )
        outputDirectory.set(layout.buildDirectory.dir("generated/uniffi"))
        // uniffi-bindgen reads the cdylib that the rust plugin's cargoBuild
        // produces, so the Rust build must run first.
        dependsOn("cargoBuild")
    }

// Register the generated bindings on every variant's Kotlin source set via the
// AGP 9 Variant API (the supported replacement for `sourceSets { srcDir(...) }`).
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            generateUniffiBindings,
            GenerateUniffiBindings::outputDirectory,
        )
    }
}
