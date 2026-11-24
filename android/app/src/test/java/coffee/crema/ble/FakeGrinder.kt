package coffee.crema.ble

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter

/**
 * A scripted BOOKOO Motto 80 behind a [BleTransport]: it records what the app
 * writes and streams the notifications a test scripts, framed the way the
 * grinder frames them at the default MTU (BOOKOO README §5: an 8-byte
 * header, 12 JSON bytes per 20-byte notification). Its clock is [clock];
 * each notification is stamped with it.
 */
class FakeGrinder(
    /** The services discovery reports (the Motto's by default). */
    private val services: List<String> = listOf(SERVICE),
    /** The characteristics' properties discovery reports, by UUID (the
     *  Motto's by default); none under a service it doesn't report. */
    private val characteristics: Map<String, Set<GattProperty>> = mapOf(
        RX to setOf(GattProperty.WRITE, GattProperty.WRITE_WITHOUT_RESPONSE),
        TX to setOf(GattProperty.NOTIFY),
    ),
) : BleTransport {
    val clock = AtomicLong(1_000)
    val writes = CopyOnWriteArrayList<ByteArray>()
    val writeTypes = CopyOnWriteArrayList<Boolean>()
    @Volatile var connects = 0
    @Volatile var mtuRequests = 0
    private val state = MutableStateFlow(BleTransport.ConnState.DISCONNECTED)
    private val notifications = MutableSharedFlow<BleTransport.Notification>(extraBufferCapacity = 4096)
    @Volatile var subscribers = 0
        private set

    object Handle : BleTransport.DeviceHandle {
        override val name: String = NAME
        override val address: String = "AA:BB:CC:DD:EE:01"
    }

    override fun scan(matches: (name: String) -> Boolean): Flow<BleTransport.ScanMatch> = emptyFlow()
    /** Whether a remembered address resolves without a scan (a real radio). */
    @Volatile var resolvesByAddress = true
    override fun resolveByAddress(address: String, name: String?): BleTransport.DeviceHandle? =
        if (resolvesByAddress && address == Handle.address) Handle else null
    override suspend fun connect(device: BleTransport.DeviceHandle) {
        connects++
        state.value = BleTransport.ConnState.CONNECTED
    }
    override suspend fun disconnect(device: BleTransport.DeviceHandle) {
        state.value = BleTransport.ConnState.DISCONNECTED
    }
    override fun connectionState(device: BleTransport.DeviceHandle): StateFlow<BleTransport.ConnState> = state
    override fun observe(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): Flow<BleTransport.Notification> {
        subscribers++
        return notifications.filter { it.characteristic == characteristic }
    }
    override suspend fun write(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID, data: ByteArray, withoutResponse: Boolean) {
        check(characteristic == UUID.fromString(RX)) { "write to $characteristic" }
        writes += data
        writeTypes += withoutResponse
    }
    override suspend fun read(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): ByteArray = error("no reads")
    override fun discoveredServices(device: BleTransport.DeviceHandle): List<String> = services
    override fun characteristicProperties(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): Set<GattProperty> =
        if (services.any { it.equals(service.toString(), ignoreCase = true) }) characteristics[characteristic.toString()].orEmpty() else emptySet()
    override suspend fun requestLargeMtu(device: BleTransport.DeviceHandle): Int? {
        mtuRequests++
        return 247
    }

    /** Send one message as the grinder would: 20-byte frames, all at [atMs].
     *  The frames at [lost] never arrive. */
    fun send(json: String, seq: Int = 1, atMs: Long = clock.get(), lost: Set<Int> = emptySet()) {
        for ((i, frame) in frames(json.toByteArray(Charsets.UTF_8), seq).withIndex()) {
            if (i in lost) continue
            check(notifications.tryEmit(BleTransport.Notification(UUID.fromString(TX), frame, atMs)))
        }
    }

    /** The link drops (out of range, power off). */
    fun drop() {
        state.value = BleTransport.ConnState.FAILED
    }

    companion object {
        const val NAME = "BOOKOO MT80 1a2b3c4d"
        const val SERVICE = "4d543830-0001-4b80-8f00-424f4f4b4f4f"
        const val RX = "4d543830-0002-4b80-8f00-424f4f4b4f4f"
        const val TX = "4d543830-0003-4b80-8f00-424f4f4b4f4f"
        const val SERIAL = "SN0000000MT12355"
        const val SSID = "BK-WIFI"

        fun frames(message: ByteArray, seq: Int, packet: Int = 20): List<ByteArray> {
            val cap = packet - 8
            return (message.indices step cap).map { off ->
                val out = ByteArrayOutputStream()
                fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
                out.write(0xA5); out.write(0x01); u16(seq); u16(message.size); u16(off)
                out.write(message, off, minOf(cap, message.size - off))
                out.toByteArray()
            }
        }

        fun base(release: Long = 103_111_020) =
            """{"broadcast":{"baseInfo":{"releaseVer":$release,"snCode":"$SERIAL","wifiName":"$SSID"}}}"""

        fun period(state: String, total: Int, gap: Int = 480) =
            """{"broadcast":{"periodInfo":{"feedingRpm":40,"bladeGap":$gap,"grindRpm":900,"humidity":30,"devState":"$state","netState":"CONNECTED","totalGrinds":$total,"cupDetect":true,"autoStop":true,"fastClean":true,"brightness":5,"standbySec":60,"selectPreset":-1}}}"""
    }
}
