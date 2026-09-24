package coffee.crema.decent

import coffee.crema.core.ShotMachine
import coffee.crema.history.StoredShot
import coffee.crema.ui.TelemetrySample
import coffee.crema.visualizer.wireShotJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * A Crema shot as the decaid `ShotRecord` Decent's shot-history ingest
 * accepts. The converter itself lives in the core
 * (`de1_domain::decent_shot_record`, one contract for both shells, pinned by a
 * golden fixture); this file only bridges Android's flat [StoredShot] into the
 * Rust wire shape via [wireShotJson] — the same bridge the Visualizer upload
 * uses — and hands it over.
 *
 * The upload variant of the wire (not `forBackup`) is the right one: it carries
 * the effective grind (recorded, else the bag's reference), the bean snapshot
 * incl. its grinder, the equipment grinder model, the yield target, the
 * embedded profile and its name — everything the record reads — and leaves out
 * the local-only ids.
 */

/**
 * Build the ShotRecord JSON. [fullSamples] replaces the stored (downsampled)
 * series when the caller still holds the full-resolution buffer (the live-shot
 * path); [grinderModel] is the equipment-level grinder from Settings.
 */
fun decentShotRecordJson(
    core: DecentCore,
    json: Json,
    shot: StoredShot,
    machine: ShotMachine,
    appVersion: String,
    grinderModel: String? = null,
    fullSamples: List<TelemetrySample>? = null,
): String {
    val source = fullSamples?.takeIf { it.isNotEmpty() }?.let { shot.copy(samples = it) } ?: shot
    val wire = wireShotJson(source, grinderModel = grinderModel?.takeIf { it.isNotBlank() })
    return core.shotRecordJson(
        json.encodeToString(JsonObject.serializer(), wire),
        json.encodeToString(ShotMachine.serializer(), machine),
        appVersion,
    )
}

/**
 * Human model name for the DE1 MMR `MachineModel` register, via the core's
 * table — null for 0 / an unknown value (the record then omits `model`).
 */
fun decentModelName(raw: UInt?, name: (UInt) -> String = { coffee.crema.core.machineModelName(it) }): String? {
    if (raw == null || raw == 0u) return null
    return name(raw).takeUnless { it.isBlank() || it == "unknown" || it.startsWith("model ") }
}
