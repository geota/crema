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

// local.properties (gitignored): sdk.dir plus optional dev overrides
// (visualizerClientId, rust.cargoCommand) and release signing (release.*). Read once.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Short git SHA stamped into BuildConfig.GIT_SHA so a crash report names the
// exact build that failed (debug builds all share versionName "0.1"). Best-effort.
val gitSha: String = runCatching {
    ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
        .inputStream.bufferedReader().use { it.readText().trim() }
        .ifEmpty { "unknown" }
}.getOrDefault("unknown")

// Release signing — from CI env (the release workflow base64-decodes the keystore to
// a file and exports KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD) or
// from local.properties (release.keystoreFile / .storePassword / .keyAlias /
// .keyPassword). When none are present the `release` build stays UNSIGNED — CI still
// publishes an installable debug-signed APK alongside it.
val releaseKeystoreFile = (System.getenv("KEYSTORE_FILE") ?: localProps.getProperty("release.keystoreFile"))
    ?.let { rootProject.file(it) }?.takeIf { it.exists() }
val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD") ?: localProps.getProperty("release.storePassword")
val releaseKeyAlias = System.getenv("KEY_ALIAS") ?: localProps.getProperty("release.keyAlias")
val releaseKeyPassword = System.getenv("KEY_PASSWORD") ?: localProps.getProperty("release.keyPassword")
val hasReleaseSigning = releaseKeystoreFile != null &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "coffee.crema"
    compileSdk = 37
    // Pinned to the NDK installed via the SDK Manager ("NDK (Side by side)").
    // AGP's built-in default NDK version differs, so without an explicit pin
    // Gradle looks for a version that isn't installed and fails with
    // "NDK is not installed". Update this if you install a different NDK.
    ndkVersion = "30.0.14904198"

    defaultConfig {
        // Published app identity — permanent once on Play. Intentionally differs
        // from `namespace` ("coffee.crema", the compile-time R/BuildConfig package):
        // AGP decouples the two, so the store id can change without a package-wide
        // source refactor. Every `coffee.crema.*` Kotlin symbol stays put.
        applicationId = "dev.maceiras.crema"
        // Teclast P25T tablet runs Android 12 (API 31); also the phone floor.
        minSdk = 31
        // AGP 9 defaults targetSdk to compileSdk, but pin it explicitly so the
        // value is unambiguous and survives a compileSdk bump.
        targetSdk = 36
        // CI derives these from the release tag (-PversionName=0.2.0 -PversionCode=<run>);
        // local/dev builds fall back to the committed defaults.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1"
        // Stamped into the crash report so it names the exact build that failed.
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        // Reversed-client-id redirect scheme for the Google Drive OAuth flow,
        // overridden per build type from the resolved Drive client_id. A safe
        // never-matching default keeps the manifest valid when unconfigured.
        manifestPlaceholders["googleRedirectScheme"] = "com.googleusercontent.apps.unconfigured"
    }

    // JVM unit tests (`./gradlew :app:testDebugUnitTest`) for the pure shell logic
    // — wire assembly, fingerprint-skip decisions, formatters. They never touch the
    // BLE/DE1 or the native FFI (those need a device), so stubbed android.* calls
    // return defaults rather than throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Visualizer Doorkeeper application client_id (a PUBLIC client — PKCE, no
    // secret to keep). Per-environment like the web's VITE_VISUALIZER_CLIENT_ID.
    // Resolution order: -PvisualizerClientId=… → VISUALIZER_CLIENT_ID env var →
    // (debug only) visualizerClientId in the gitignored local.properties.
    // Nothing is committed; release has no baked fallback — the production OID
    // must come from the flag/env. Blank = Settings → Sharing renders
    // "not configured", same as web.
    val visualizerClientIdOverride =
        (project.findProperty("visualizerClientId") as String?)
            ?: System.getenv("VISUALIZER_CLIENT_ID")
    val visualizerClientIdDev = run {
        // local.properties is AGP-only (sdk.dir) — not auto-exposed as a Gradle
        // property, so read it explicitly.
        val f = rootProject.file("local.properties")
        if (!f.exists()) {
            ""
        } else {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty("visualizerClientId") ?: ""
        }
    }

    // Google Drive OAuth client_id (drive.file backup) — a PUBLIC client, same
    // resolution as the Visualizer id: -PgoogleDriveClientId → GOOGLE_DRIVE_CLIENT_ID
    // env → (debug) googleDriveClientId in the gitignored local.properties.
    val googleDriveClientIdOverride =
        (project.findProperty("googleDriveClientId") as String?)
            ?: System.getenv("GOOGLE_DRIVE_CLIENT_ID")
    val googleDriveClientIdDev = run {
        val f = rootProject.file("local.properties")
        if (!f.exists()) {
            ""
        } else {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty("googleDriveClientId") ?: ""
        }
    }

    // Created only when a keystore is available (CI env or local.properties) — see
    // the `hasReleaseSigning` wiring above the `android {}` block.
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField(
                "String",
                "VISUALIZER_CLIENT_ID",
                "\"${visualizerClientIdOverride ?: visualizerClientIdDev}\"",
            )
            buildConfigField(
                "String",
                "GOOGLE_DRIVE_CLIENT_ID",
                "\"${googleDriveClientIdOverride ?: googleDriveClientIdDev}\"",
            )
            manifestPlaceholders["googleRedirectScheme"] =
                (googleDriveClientIdOverride ?: googleDriveClientIdDev).let { cid ->
                    if (cid.endsWith(".apps.googleusercontent.com")) {
                        "com.googleusercontent.apps." + cid.removeSuffix(".apps.googleusercontent.com")
                    } else {
                        "com.googleusercontent.apps.unconfigured"
                    }
                }
        }
        release {
            isMinifyEnabled = false
            // Signed when a release keystore is configured; otherwise an unsigned
            // release APK is still produced (e.g. to sign locally).
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            buildConfigField(
                "String",
                "VISUALIZER_CLIENT_ID",
                "\"${visualizerClientIdOverride ?: ""}\"",
            )
            buildConfigField(
                "String",
                "GOOGLE_DRIVE_CLIENT_ID",
                "\"${googleDriveClientIdOverride ?: ""}\"",
            )
            manifestPlaceholders["googleRedirectScheme"] =
                (googleDriveClientIdOverride ?: "").let { cid ->
                    if (cid.endsWith(".apps.googleusercontent.com")) {
                        "com.googleusercontent.apps." + cid.removeSuffix(".apps.googleusercontent.com")
                    } else {
                        "com.googleusercontent.apps.unconfigured"
                    }
                }
        }
        // Nightly / dev train (APK pipeline): the SAME app as stable —
        // dev.maceiras.crema, NOT a separate package. Inherits `release` wholesale
        // (prod OAuth clients, redirect scheme, unminified), so the only deltas are
        // a "-nightly" version marker and the signing fallback below. Built
        // per-commit from `main` by .github/workflows/nightly.yml (fast debug cargo
        // profile) and published as a rolling prerelease; because it shares the
        // release signature, Obtainium updates in place between a nightly and a
        // tagged stable release. versionCode (set by the workflow) sits far above
        // any stable semver code, so nightly→stable is a downgrade Obtainium won't
        // auto-apply — that direction needs a manual reinstall.
        create("nightly") {
            initWith(getByName("release"))
            versionNameSuffix = "-nightly"
            // Release-signed in CI (KEYSTORE_BASE64) so it shares a signature with
            // the tagged release; debug-signed locally so a dev build still installs.
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    // collectAsStateWithLifecycle — lifecycle-aware Flow collection (pauses at STOPPED).
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Google Fonts (Newsreader / Hanken Grotesk / JetBrains Mono) for CremaTheme's
    // Type.kt. BOM-managed (no explicit version). The GMS font-provider certs ship
    // in this artifact; on a device without the provider, fonts fall back to the
    // system families and the type scale still renders.
    implementation("androidx.compose.ui:ui-text-google-fonts")
    // Navigation for the 6-destination rail + 2 pushed editors (AppNavHost).
    implementation("androidx.navigation:navigation-compose:2.9.8")
    // Phosphor icons as Compose ImageVectors — the PhIcon binding in
    // CremaComponents maps each screen's kebab-case glyph name to a regular-weight
    // vector. Pure-Kotlin vector lib (no Compose-compiler version coupling). NOTE:
    // with isMinifyEnabled=false the full icon set ships in the APK; enabling R8
    // (release) prunes it to just the ~18 referenced glyphs.
    implementation("com.adamglin:phosphor-icon:1.0.0")
    // Coil 3 (Compose-native image loading) — renders bean bag photos from
    // filesDir/bean-images via AsyncImage/SubcomposeAsyncImage. Local files only,
    // so coil-network-* is intentionally NOT included. The default singleton
    // ImageLoader is used (no Application SingletonImageLoader.Factory needed).
    //
    // Coil 3.5.0 pulls kotlin-stdlib 2.4.0 — fine now the project is on the Kotlin
    // 2.4.0 compiler (reads ≤2.4.0 metadata). Keep Coil's transitive stdlib ≤ the
    // project's Kotlin, or the generated UniFFI bindings (de1_ffi.kt) won't compile.
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // UniFFI's generated Kotlin depends on JNA for the FFI calls and on
    // kotlinx-coroutines (its runtime helpers reference it).
    // NEVER drop below 5.17.0 — that was the first JNA whose Android
    // libjnidispatch.so ships 16 KB ELF page alignment, required by Android 15+ /
    // API 36+ devices + emulators (16 KB pages); older JNA fails to dlopen there.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Visualizer HTTP — OkHttp because HttpURLConnection rejects the PATCH
    // verb (shot edits sync via PATCH /api/shots/{id}).
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // The de1-app `CoreOutput` JSON is deserialized with kotlinx.serialization.
    // The generated `core/bindings/crema-core.kt` types are @Serializable.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Charts are hand-rolled Compose Canvas (CanvasShotChart, ProfileCurveChart) —
    // a faithful port of the web PWA's uPlot design (shared 0–10 scale, temp/weight
    // ÷10, dual-labelled axes, playhead, draggable profile curve). No chart library:
    // a 3-way bake-off (Canvas vs ComposeCharts vs Charty) found both libs are
    // index-x category plotters with no real-seconds axis, no dual axis, and no
    // plot-coordinate access (so no playhead / no editor drag). Canvas owns the
    // value→pixel transform, so the whole design is just arithmetic.

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
    val nordicBle = "2.0.0-beta01"
    implementation("no.nordicsemi.kotlin.ble:core:$nordicBle")
    implementation("no.nordicsemi.kotlin.ble:client-android:$nordicBle")
    implementation("no.nordicsemi.kotlin.ble:environment-android:$nordicBle")

    // Ktor — the multi-device LAN proxy (M1). The PRIMARY embeds a small
    // WebSocket server (`LanRelayServer`) so secondaries can mirror/drive the
    // DE1 over the LAN; a SECONDARY dials it as a Ktor WebSocket client. CIO
    // (coroutine IO) on both ends — pure-Kotlin, no Netty, light on Android.
    // Server + client sessions are both `WebSocketSession`, so ONE
    // `KtorWsFrameLink` backs both. The same framed protocol later serves the
    // PWA (M4) and a cloud relay (M5); the JSON frames ride as WS text.
    val ktor = "3.5.0"
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-websockets:$ktor")

    // JVM unit tests for pure shell logic (no device / FFI). kotlin-test mapped
    // onto the JUnit 4 runner AGP's unit-test task uses.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.0")
    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------------------
// Rust / NDK integration — net.mullvad.rust-android (Mullvad fork)
// ---------------------------------------------------------------------------
// `cargoBuild` compiles the `de1-ffi` cdylib for the listed Android ABIs and
// writes `libde1_ffi.so` into build/rustJniLibs/android/<abi>/. The wiring
// below feeds that directory into AGP's `merge*JniLibFolders` tasks so it
// ships in the APK. The NDK and the Rust target must be installed (see
// android/README.md).
//
// Native-lib build profile: "debug" (fast — local dev default) or "release"
// (LTO + codegen-units=1 + opt-3, per core's [profile.release]). CI's release
// job passes -PcargoProfile=release so shippable APKs carry a fully optimized .so.
val cargoProfile = (project.findProperty("cargoProfile") as String?) ?: "debug"

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
    profile = cargoProfile
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
                "../../core/target/aarch64-linux-android/$cargoProfile/libde1_ffi.so",
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
