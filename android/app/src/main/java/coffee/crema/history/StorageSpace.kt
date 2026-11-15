package coffee.crema.history

/*
 * The storage notices (#310 P6a, SPEC §1). With no cap on the history, the
 * real limit is the disk, so the app watches the free space where the history
 * lives (`filesDir.usableSpace`), not a row count. Nothing here deletes: a
 * notice offers Back up and Free up space, and the user chooses.
 */

/** How full the device is, for the history's notices. */
enum class StorageLevel {
    OK,

    /** Under [StorageSpace.GETTING_FULL_BYTES]: one notice, with Back up and Free up space. */
    GETTING_FULL,

    /** Under [StorageSpace.NEARLY_FULL_BYTES]: a lasting banner. */
    NEARLY_FULL,
}

object StorageSpace {
    const val MB = 1024L * 1024
    const val GETTING_FULL_BYTES = 500 * MB
    const val NEARLY_FULL_BYTES = 100 * MB

    fun levelOf(freeBytes: Long): StorageLevel = when {
        freeBytes < NEARLY_FULL_BYTES -> StorageLevel.NEARLY_FULL
        freeBytes < GETTING_FULL_BYTES -> StorageLevel.GETTING_FULL
        else -> StorageLevel.OK
    }

    /** [bytes] for people: "12 KB", "3.4 MB", "1.20 GB". */
    fun format(bytes: Long): String = when {
        bytes < MB -> "${maxOf(1, bytes / 1024)} KB"
        bytes < 1024 * MB -> "%.1f MB".format(bytes / MB.toDouble())
        else -> "%.2f GB".format(bytes / (1024.0 * MB))
    }
}

/**
 * What Free up space would delete (#310 P6a): the unpinned shots dated before
 * [beforeMs], named one by one, and about the space they take. Pinned shots
 * are never in it ([pinnedKept] says how many it spares). Only the ids named
 * here can go, and only if they're still unpinned and still older when the
 * user confirms.
 */
data class FreeUpPlan(
    val beforeMs: Long,
    val ids: List<String>,
    val pinnedKept: Int,
    val bytes: Long,
    /** Shots from then kept because the last backup left them out or wrote them without their curve (REVIEW-437 B1). */
    val backupKept: Int = 0,
) {
    companion object {
        /** In [coffee.crema.ui.MainUiState.backupIncomplete]: a backup left out a shot it couldn't name. */
        const val UNKNOWN_SHOT = "*"
    }
}

/** What Free up space did: shots deleted, and planned ones it kept because the last backup didn't hold them whole. */
data class FreeUpResult(val deleted: Int, val backupKept: Int)

/** Whether [id] isn't whole in the last backup ([incomplete], [coffee.crema.ui.MainUiState.backupIncomplete]). */
fun backupLacks(id: String, incomplete: Set<String>): Boolean = id in incomplete || FreeUpPlan.UNKNOWN_SHOT in incomplete
