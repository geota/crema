package coffee.crema.ble.proxy

import coffee.crema.ble.BleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every [BleTransport] decorator must forward every member that has a default
 * body. A member left to the default silently turns the real radio's
 * behaviour off behind the decorator (it happened to [SwitchableBleTransport]
 * with the reconnect methods, and to [TappingBleTransport] with
 * `isLinkHeldByStack`). Add new decorators to [decorators].
 *
 * The check is behavioural: each decorator wraps a recording delegate, every
 * defaulted member is called on the decorator, and the call must reach the
 * delegate. (A declared-method check can't tell: Kotlin emits a stub for each
 * inherited default in the implementing class.)
 */
class BleTransportDecoratorTest {

    private val handle = object : BleTransport.DeviceHandle {
        override val name: String = "DE1"
        override val address: String = "AA:BB"
    }

    private fun decorators(delegate: BleTransport, scope: CoroutineScope): List<BleTransport> = listOf(
        SwitchableBleTransport(delegate),
        TappingBleTransport(
            delegate,
            RelayHub("P", roster = { emptyList() }, readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false }),
            scope,
        ),
    )

    @Test
    fun `every decorator forwards every defaulted BleTransport member`() {
        val defaulted = BleTransport::class.java.methods.filter { it.isDefault }
        assertTrue(defaulted.size >= 5, "expected BleTransport's defaulted members, found ${defaulted.map { it.name }}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val calls = mutableListOf<String>()
            val delegate = recordingDelegate(calls)
            for (decorator in decorators(delegate, scope)) {
                val missing = defaulted.filter { m ->
                    calls.clear()
                    // The dummy results may not fit what a decorator does with
                    // them; only whether the call reached the delegate matters.
                    runCatching { m.invoke(decorator, *m.parameterTypes.map(::dummy).toTypedArray()) }
                    m.name !in calls
                }
                assertTrue(missing.isEmpty(), "${decorator::class.simpleName} does not forward ${missing.map { it.name }}")
            }
        } finally {
            scope.cancel()
        }
    }

    /** A [BleTransport] whose every member (defaults included) records its name. */
    private fun recordingDelegate(calls: MutableList<String>): BleTransport =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BleTransport::class.java)) { _, m: Method, _ ->
            calls += m.name
            when (m.returnType) {
                java.lang.Boolean.TYPE -> false
                BleTransport.DeviceHandle::class.java -> handle
                StateFlow::class.java -> MutableStateFlow(BleTransport.ConnState.DISCONNECTED)
                Flow::class.java -> emptyFlow<Any>()
                List::class.java -> emptyList<String>()
                ByteArray::class.java -> ByteArray(0)
                else -> Unit // suspend members return through their continuation
            }
        } as BleTransport

    private val noop: () -> Unit = {}
    private val matchAll: (String) -> Boolean = { true }

    private fun dummy(type: Class<*>): Any = when (type) {
        BleTransport.DeviceHandle::class.java -> handle
        String::class.java -> "AA:BB"
        java.lang.Long.TYPE -> 1L
        java.lang.Boolean.TYPE -> false
        UUID::class.java -> UUID(0, 0)
        Function0::class.java -> noop
        Function1::class.java -> matchAll
        Continuation::class.java -> Continuation<Any?>(EmptyCoroutineContext) {}
        else -> error("no dummy for $type")
    }
}
