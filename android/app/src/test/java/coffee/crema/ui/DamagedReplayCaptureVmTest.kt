package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SaveFloor
import coffee.crema.persist.Stores
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * geota/crema#576 as it happened: a hub replaying a capture as its DE1 (the
 * emulator setup for the Play screenshots) was hard-killed while recording,
 * the newest `session-*.jsonl` came back zero-filled, and parsing it threw
 * "Unexpected JSON token at offset 0" out of the ViewModel's construction on
 * every start. Now the damaged capture is kept aside and the next one plays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DamagedReplayCaptureVmTest {
    private val store = ViewModelStore()
    private val app get() = VmHarness.app()
    private val captures get() = File(app.filesDir, "captures")
    private val older get() = File(captures, "session-20261101-090000.jsonl")
    private val newest get() = File(captures, "session-20261125-090000.jsonl")

    private val goodCapture = """{"t":1,"dir":"in","src":"DE1_STATE","hex":"0200"}""" + "\n" +
        """{"t":2,"dir":"in","src":"DE1_STATE","hex":"0200"}""" + "\n"

    @Before fun setUp() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        SaveFloor.resetForTest()
        File(app.filesDir, "prefs.json").writeText("""{"proxyRole":"primary","replayPrimary":true}""")
        captures.mkdirs()
    }

    @After fun tearDown() {
        store.clear()
        VmHarness.idle()
        SafeFile.resetForTest()
        Stores.resetForTest()
    }

    private fun startedVm(): MainViewModel =
        VmHarness.build(store).also { VmHarness.idle(); VmHarness.awaitStartupLoaded(it) }

    private fun keptAside(f: File) = captures.listFiles { c -> c.name.startsWith(f.name + ".corrupt-") }.orEmpty().toList()

    @Test
    fun aZeroFilledCaptureIsKeptAsideAndTheNextOnePlays() {
        older.writeText(goodCapture)
        older.setLastModified(1_000_000)
        val zeros = ByteArray(4096)
        newest.writeBytes(zeros)
        newest.setLastModified(2_000_000)

        val vm = startedVm()

        assertTrue(VmHarness.log(vm).any { "PRIMARY (replay ${older.name})" in it }, "the older capture plays")
        assertFalse(newest.exists(), "no later start picks the damaged capture again")
        val kept = keptAside(newest).single()
        assertContentEquals(zeros, kept.readBytes(), "kept aside byte for byte, not deleted")
        assertEquals(goodCapture, older.readText())
        assertEquals("Couldn't read your replay capture; the damaged file was kept aside.", vm.ui.value.keptAsideNotice)
    }

    @Test
    fun aZeroFilledBlockInsideTheHexIsKeptAsideToo() {
        // The block starts in one line's hex and ends in a later one's: both
        // lines still decode as JSON (raw NULs inside a string), only the hex
        // doesn't. It used to throw from the transport's constructor.
        val holed = """{"t":1,"dir":"in","src":"DE1_STATE","hex":"02""" + "\u0000".repeat(40) + """00"}""" + "\n"
        newest.writeText(holed)

        val vm = startedVm()

        assertTrue(VmHarness.log(vm).any { "PRIMARY (live BLE)" in it })
        assertEquals(holed, keptAside(newest).single().readText())
        assertEquals("Couldn't read your replay capture; the damaged file was kept aside.", vm.ui.value.keptAsideNotice)
    }

    @Test
    fun aCaptureCutOffMidLineIsKeptAsideAndTheHubUsesBluetooth() {
        val cut = goodCapture + """{"t":3,"dir":"in","sr"""
        newest.writeText(cut)

        val vm = startedVm()

        assertTrue(VmHarness.log(vm).any { "PRIMARY (live BLE)" in it }, "no capture left to play: live Bluetooth")
        assertEquals(cut, keptAside(newest).single().readText())
        assertEquals("primary", vm.ui.value.proxyRole)
    }
}
