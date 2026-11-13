package coffee.crema.faults

/**
 * Fault injection for the owner's hardware run (`.scratch/machine-control/
 * DESIGN.md` §7, H2, H11, H14, H15): the shell's write and notify lanes ask it
 * whether to drop what they are about to send or feed. Drops happen in the
 * shell, never in the core, so the core sees exactly what a lost write or a
 * silent scale looks like.
 *
 * Debug builds only. The one way to get an injector is [installedFaults],
 * defined once per build: `src/debug` returns the armable one, and
 * `src/shipped` (release and nightly) returns [NoFaults], whose answers are
 * constants. A shipped build has no code that drops anything
 * (`ShippedHasNoFaultInjectionTest`).
 */
interface FaultInjector {
    /** A `Command.Stop` write is about to go out: `true` drops it. */
    fun dropStop(): Boolean

    /** A plain requested-state write (not a `Command.Stop`) is about to go
     *  out: `true` drops it. */
    fun dropRequestedState(): Boolean

    /** A scale weight frame arrived at [nowMs] (`SystemClock.elapsedRealtime`):
     *  `true` drops it before it is recorded or reaches the core. */
    fun dropWeightFrame(nowMs: Long): Boolean
}

/** Nothing is ever dropped: every shipped build's injector. */
object NoFaults : FaultInjector {
    override fun dropStop(): Boolean = false
    override fun dropRequestedState(): Boolean = false
    override fun dropWeightFrame(nowMs: Long): Boolean = false
}
