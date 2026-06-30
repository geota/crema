package coffee.crema

import android.app.Application
import coffee.crema.core.installPanicLogger
import coffee.crema.diag.CrashReporter

/**
 * App entry point — installs crash diagnostics before anything else runs.
 *
 * Order matters: the Rust panic hook and the JVM uncaught-exception handler go
 * in first, so a crash anywhere downstream (FFI load, BLE auto-connect, the UI)
 * is captured. The safe-mode boot marker + recovery gate live in
 * `MainActivity` — they need the Activity to show the recovery screen before
 * auto-connect runs.
 */
class CremaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Rust side: panics get their file:line + backtrace logged (even the
        // ones UniFFI catches and rethrows). Wrapped — a failed `.so` load must
        // not itself brick boot.
        runCatching { installPanicLogger(CrashReporter.rustPanicLogPath(this)) }
        // JVM side: uncaught Kotlin/Java exceptions, incl. UniFFI-rethrown panics.
        CrashReporter.install(this)
    }
}
