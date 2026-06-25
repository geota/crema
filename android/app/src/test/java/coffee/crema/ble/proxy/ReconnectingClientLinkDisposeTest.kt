package coffee.crema.ble.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * Issue 10: `dispose()` must close the inbox so a consumer of `incoming()` (the
 * ProxyTransport dispatch flow) completes instead of suspending forever on the dead
 * link — otherwise that collector leaks across every mode switch. No server is
 * needed; the inbox lifecycle is independent of whether a session ever connected.
 */
class ReconnectingClientLinkDisposeTest {
    @Test
    fun `dispose closes the inbox so incoming() completes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Port 1 never connects — the redial loop just backs off; we only assert
            // the inbox lifecycle, not connectivity.
            val link = ReconnectingClientLink("ws://127.0.0.1:1/de1-bridge", scope)
            link.dispose()
            // Completes (drains the closed, empty channel) rather than hanging.
            withTimeout(2_000) { link.incoming().collect { } }
        } finally {
            scope.cancel()
        }
    }
}
