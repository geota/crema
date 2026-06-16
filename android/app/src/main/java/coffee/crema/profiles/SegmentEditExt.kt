package coffee.crema.profiles

/*
 * SegmentEdit / SegmentExit display helpers shared by the tablet (ProfileEditScreen)
 * and phone (PhoneProfileEditScreen) segment editors. The `isPressure → bar/ml/s`
 * unit mapping was reimplemented at 8+ sites, and ProfileSegment.toEdit() was
 * `private` in ProfileEditScreen and re-inlined by the phone (issue 28/32). Both now
 * live here, next to the SegmentEdit model.
 */

/** A segment targets pressure unless its mode is explicitly "flow". */
val SegmentEdit.isPressure: Boolean get() = mode != "flow"

/** Unit for the segment's TARGET value — bar (pressure) / ml/s (flow). */
fun SegmentEdit.targetUnit(): String = if (isPressure) "bar" else "ml/s"

/**
 * Unit for the segment's MAX LIMITER — the OTHER channel: a pressure phase caps
 * flow (ml/s), a flow phase caps pressure (bar).
 */
fun SegmentEdit.limiterUnit(): String = if (isPressure) "ml/s" else "bar"

/** Unit for an early-exit THRESHOLD — keyed off the exit's own metric. */
fun SegmentExit.unit(): String = if (metric == "pressure") "bar" else "ml/s"

/**
 * The editable [SegmentEdit] view of a stored [ProfileSegment]: an unset temp falls
 * back to the profile's [brewTemp], and the nullable mode / ramp / sensor get their
 * editor defaults. Promoted from a private copy in each shell's profile editor.
 */
fun ProfileSegment.toEdit(brewTemp: Float): SegmentEdit = SegmentEdit(
    name = name,
    mode = mode ?: "pressure",
    ramp = ramp ?: "smooth",
    target = target,
    time = time,
    temp = temp ?: brewTemp,
    tempSensor = tempSensor ?: "coffee",
    volume = volumeLimitMl,
    exit = exit,
    limiter = limiter,
)
