package coffee.crema.history

import kotlinx.coroutines.flow.SharedFlow

/**
 * The stage-1 index as History reads it (#310 A2, SPEC §7): its file, the
 * notice of each write to it, and how an index row becomes a [ShotRow]. Only
 * handed out while the index can answer for the rows
 * ([HistoryStore.indexReads]); reads only.
 */
class HistoryIndexReads internal constructor(
    internal val bridge: HistoryDbBridge,
    val notices: SharedFlow<IndexNotice>,
    val decode: (String) -> ShotRow?,
)
