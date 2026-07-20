# UniFFI/JNA FFI bridge (coffee.crema.core, generated at build time from the
# de1-ffi Rust crate — see build.gradle.kts's generateUniffiBindings task).
# JNA marshals every native call by matching field/method NAMES via reflection:
# Structure subclasses are mapped using @Structure.FieldOrder("name", ...), and
# Native.register(UniffiLib::class.java, ...) binds native symbols to interface
# method names. R8 renaming or stripping any member here doesn't crash cleanly —
# it silently corrupts the struct layout / native binding. Keep this package,
# and JNA itself, completely unobfuscated and unshrunk.
-keep class coffee.crema.core.** { *; }
-keepclassmembers class coffee.crema.core.** { *; }

-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { public *; }
-keepclassmembers class * extends com.sun.jna.Structure$ByValue { public *; }
-keepclassmembers class * extends com.sun.jna.Structure$ByReference { public *; }
-keep interface com.sun.jna.Library { *; }
# JNA's aar ships desktop-only code paths (Windows/macOS dynamic loading) that
# never execute on Android but that R8 can't resolve on this classpath.
-dontwarn com.sun.jna.**

# Ktor's IntelliJ-debugger detector references desktop-JVM-only management
# classes that don't exist on Android. Dead code path at runtime (Android
# never satisfies the check that reaches it) — just missing at the bytecode
# level, so R8 needs telling not to fail the build over it.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
