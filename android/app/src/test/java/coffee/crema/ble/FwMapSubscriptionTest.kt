package coffee.crema.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The A009 re-subscribe before verify really re-arms the CCCD (audit r2
 * LOW 4): the first subscribe collects (CCCD enable), a later one keeps that
 * collection and re-arms with an explicit disable + enable, frames reach the
 * latest callback, and nothing ever leaves notifications disabled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FwMapSubscriptionTest {
    private object Handle : BleTransport.DeviceHandle {
        override val address = "AA:BB"
        override val name: String? = null
    }

    @Test
    fun aResubscribeReArmsTheCccdInsteadOfStackingCollectors() = runTest {
        val wire = mutableListOf<String>()
        val frames = MutableSharedFlow<ByteArray>()
        var collectors = 0
        val sub = FwMapSubscription(
            device = Handle,
            scope = backgroundScope,
            observe = {
                callbackFlow {
                    collectors++
                    wire += "enable"
                    val job = launch { frames.collect { send(it) } }
                    // Like Nordic: ending a collection does NOT write the CCCD.
                    awaitClose { job.cancel() }
                }
            },
            rearm = {
                wire += "disable"
                wire += "enable"
            },
        )
        val first = mutableListOf<Int>()
        val second = mutableListOf<Int>()
        sub.subscribe { first += it[0].toInt() }
        runCurrent()
        frames.emit(byteArrayOf(1))
        runCurrent()
        sub.subscribe { second += it[0].toInt() } // before verify
        runCurrent()
        frames.emit(byteArrayOf(2))
        runCurrent()
        assertEquals(1, collectors, "one collection")
        assertEquals(listOf("enable", "disable", "enable"), wire, "re-armed, ending enabled")
        assertEquals(listOf(1), first)
        assertEquals(listOf(2), second, "frames go to the latest callback")
        sub.cancel()
        runCurrent()
        sub.subscribe {}
        runCurrent()
        assertEquals(2, collectors, "after a cancel a new collection starts")
    }
}
