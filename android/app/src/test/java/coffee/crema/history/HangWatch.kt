package coffee.crema.history

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A per-test hang watch that works under Robolectric (#310 A5). JUnit's own
 * `Timeout` runs the test on another thread, which Robolectric's main looper
 * doesn't allow. This runs the test where it is. After [seconds] it prints
 * every thread's stack to stderr, so the report names the thread that holds
 * what the test waits on, and interrupts the test thread so the test fails
 * instead of hanging the run. The Gradle task's own timeout is the backstop.
 */
class HangWatch(private val seconds: Long = 180) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val test = Thread.currentThread()
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            val watch = Thread({
                try {
                    Thread.sleep(seconds * 1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (done.get()) return@Thread
                val out = StringBuilder("HangWatch: ${description.displayName} still running after ${seconds}s\n")
                for ((t, stack) in Thread.getAllStackTraces()) {
                    out.append("\"").append(t.name).append("\" ").append(t.state).append('\n')
                    for (f in stack) out.append("    at ").append(f).append('\n')
                }
                System.err.println(out)
                test.interrupt()
            }, "HangWatch").apply { isDaemon = true }
            watch.start()
            try {
                base.evaluate()
            } finally {
                done.set(true)
                watch.interrupt()
            }
        }
    }
}
