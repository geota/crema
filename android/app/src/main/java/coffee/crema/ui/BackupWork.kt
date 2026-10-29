package coffee.crema.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The backup's threading contract: [snapshot] reads live state on the
 * caller's thread (Main, where that state is mutated), then [build] — the
 * heavy serialisation, core export and zip — runs on [dispatcher]. A null
 * snapshot ("nothing to back up") skips the build. Top-level + `internal`
 * so the contract is unit-testable without the controller.
 */
internal suspend fun <S : Any, R : Any> snapshotThenBuild(
    snapshot: () -> S?,
    dispatcher: CoroutineDispatcher,
    build: (S) -> R?,
): R? {
    val snap = snapshot() ?: return null
    return withContext(dispatcher) { build(snap) }
}

/**
 * A `.crema.zip`: `backup.jsonl` plus one `images/<RAW bean id>` entry per
 * bag photo (the cross-shell key web + Android restore by). [photos] is lazy
 * so each photo is read from disk only as it is written into the zip.
 */
internal fun zipBackupBytes(jsonl: String, photos: Sequence<Pair<String, ByteArray>>): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
        zos.putNextEntry(ZipEntry("backup.jsonl"))
        zos.write(jsonl.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
        for ((beanId, bytes) in photos) {
            zos.putNextEntry(ZipEntry("images/$beanId"))
            zos.write(bytes)
            zos.closeEntry()
        }
    }
    return baos.toByteArray()
}
