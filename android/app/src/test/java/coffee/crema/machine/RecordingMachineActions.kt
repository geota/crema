package coffee.crema.machine

import coffee.crema.core.MachineRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [MachineActions] that records what ran, and on which thread: the verb bodies
 * a test's [LocalMachine] or [RelayMachine] reached. [startShot] is what a
 * start answers; [profiles] / [beans] are this device's library.
 */
internal class RecordingMachineActions(
    var startShot: CommandOutcome = CommandOutcome.Accepted,
    val profiles: MutableSet<String> = mutableSetOf("p-1"),
    val beans: MutableSet<String> = mutableSetOf("bean-1"),
    /** This device's live grinders. */
    val grinders: MutableList<coffee.crema.core.Grinder> = mutableListOf(
        coffee.crema.core.Grinder(id = "grinder:niche-zero", name = "Niche Zero", scale = coffee.crema.beans.DEFAULT_GRIND_SCALE, updatedAt = 1L),
    ),
) : MachineActions {
    /** Verb bodies that ran, in order. */
    val applied = CopyOnWriteArrayList<String>()

    /** The thread each one ran on. */
    val threads = CopyOnWriteArrayList<String>()

    /** Config shown (the visible half). */
    val shown = CopyOnWriteArrayList<MachineCommand.Config>()

    var peerActivity = 0

    /** What [configReady] answers. */
    var configLoaded = true

    /** Hooks that throw instead of answering: `"notePeerActivity"`, `"hasProfile"`. */
    val throwing: MutableSet<String> = mutableSetOf()

    private fun maybeThrow(hook: String) {
        if (hook in throwing) throw IllegalStateException("$hook failed")
    }

    private fun ran(what: String) {
        applied += what
        // Coroutine debug mode appends " @coroutine#N" to the thread name.
        threads += Thread.currentThread().name.substringBefore(" @")
    }

    override fun applyStartShot(): CommandOutcome = startShot.also { ran("startShot") }
    override fun applyMachineState(request: MachineRequest) = ran("machineState($request)")
    override fun applyCancelMaintenance() = ran("cancelMaintenance")
    override fun applyTareScale() = ran("tareScale")
    override fun applyHotWaterDose(dose: coffee.crema.core.HotWaterDose) = ran("hotWaterDose($dose)")

    override fun showConfig(cmd: MachineCommand.Config) {
        shown += cmd
    }

    /** What a peer's bag pick answers ([commitConfig] with `relayed`): the core's selection, or none. */
    var picked: coffee.crema.core.BagSelection? = null

    override fun commitConfig(cmd: MachineCommand.Config, relayed: Boolean): coffee.crema.core.BagSelection? {
        ran("commit($cmd)")
        return picked?.takeIf { relayed && cmd is MachineCommand.SetActiveBean }
    }
    override fun hasProfile(id: String): Boolean {
        maybeThrow("hasProfile")
        return id in profiles
    }
    override fun hasBean(id: String) = id in beans
    override fun liveGrinder(id: String) = grinders.firstOrNull { it.id == id }
    override fun configReady() = configLoaded

    /** What [profilePickLock] answers: the lock's note while a shot or guided brew runs here. */
    var pickLock: String? = null

    override fun profilePickLock() = pickLock

    override fun notePeerActivity() {
        maybeThrow("notePeerActivity")
        peerActivity++
    }
}
