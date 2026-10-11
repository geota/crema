package coffee.crema.decent

/*
 * The Decent pieces of the Rust core (#84), behind one seam so the shell's
 * IO / retry / state logic is JVM-testable without the native library. The
 * contract itself (record shape, reply classification, share URL) is tested
 * in core (`de1_domain::decent_wire`, `decent_shot_record`); a unit test
 * swaps in a fake that answers with the same JSON shapes.
 */
interface DecentCore {
    /** decaid ShotRecord JSON for a Rust-wire StoredShot + a `ShotMachine`. Throws on bad input. */
    fun shotRecordJson(shotJson: String, machineJson: String, appVersion: String): String

    /** `DecentLoginReply` JSON for a `login_test` answer. */
    fun loginReplyJson(status: Int, body: String): String

    /** `DecentMachinesReply` JSON for an `sn` answer. */
    fun machinesReplyJson(status: Int, body: String): String

    /**
     * `DecentUploadReply` JSON for a `shot_upload` answer; [replace] = the
     * caller asked for `?replace=1` (a `"duplicate"` answer is then `NotReplaced`).
     */
    fun uploadReplyJson(status: Int, body: String, replace: Boolean): String

    /** The serial to file a shot under, or null when unknown (blank, or the `0` older DE1s report). */
    fun knownSerial(serial: String?): String?

    /** The public `/shot/<sn>/<id>` page, or null unless both are real. */
    fun shotViewUrl(serial: String?, decentId: String?): String?
}

/** The production core, over the UniFFI bindings. */
object UniffiDecentCore : DecentCore {
    override fun shotRecordJson(shotJson: String, machineJson: String, appVersion: String): String =
        coffee.crema.core.decentShotRecordJson(shotJson, machineJson, appVersion)

    override fun loginReplyJson(status: Int, body: String): String =
        coffee.crema.core.decentLoginTokenJson(status.toStatus(), body)

    override fun machinesReplyJson(status: Int, body: String): String =
        coffee.crema.core.decentMachinesJson(status.toStatus(), body)

    override fun uploadReplyJson(status: Int, body: String, replace: Boolean): String =
        coffee.crema.core.decentUploadReplyJson(status.toStatus(), body, replace)

    override fun knownSerial(serial: String?): String? = coffee.crema.core.decentKnownSerial(serial)

    override fun shotViewUrl(serial: String?, decentId: String?): String? =
        coffee.crema.core.decentShotViewUrl(serial, decentId)

    private fun Int.toStatus(): UShort = coerceIn(0, UShort.MAX_VALUE.toInt()).toUShort()
}
