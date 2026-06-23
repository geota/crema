package coffee.crema.beans

import android.content.Context
import java.io.File

/**
 * Bean bag photos on disk — the Android side of the cross-shell `.crema.zip`
 * photo carriage (mirrors web `$lib/bean/image-storage`).
 *
 * One JPEG/PNG file per bean under `filesDir/bean-images/`, keyed BY BEAN ID. A
 * bean record only carries a small `Bean.imageRef` marker ("this bag has a
 * photo"); the bytes live here. The actual file is always resolved by **bean
 * id**, never by parsing `imageRef` — so a backup stays cross-shell-clean:
 *
 *  - a `.crema.zip` carries each photo as `images/<rawBeanId>` (RAW id, e.g.
 *    `images/bean:abc`); on disk it lands at [beanImageFile] = a filesystem-safe
 *    form of that same id ([sanitize]).
 *  - a web backup's `imageRef` (`idb:bean-image:…`) and an Android one
 *    (`file:bean-image:…`) differ, but both shells find the photo by id.
 *
 * One [sanitize]/[beanImageFile] pair keeps capture (Phase C), the zip restore
 * ([coffee.crema.ui.MainViewModel.extractBackupJsonl]) and the zip backup
 * (Phase D) byte-for-byte aligned.
 */
object BeanImageStore {
    private const val DIR = "bean-images"

    /** The Android-local `imageRef` marker for a bean that has a stored photo.
     *  Device-specific (web uses `idb:bean-image:`); never trusted across shells. */
    fun refForBean(beanId: String): String = "file:bean-image:$beanId"

    /** Filesystem-safe filename for a bean id — raw ids contain `:` (`bean:uuid`).
     *  The SAME rule the `.crema.zip` restore uses, so capture / restore / backup
     *  all agree on where a bean's photo lives. */
    fun sanitize(beanId: String): String = beanId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** The on-disk photo file for a bean (pure path — may not exist; does no I/O). */
    fun beanImageFile(context: Context, beanId: String): File =
        File(File(context.filesDir, DIR), sanitize(beanId))

    /** Whether a bean has a non-empty photo on disk. */
    fun exists(context: Context, beanId: String): Boolean =
        beanImageFile(context, beanId).let { it.isFile && it.length() > 0L }

    /** Persist a bean's photo bytes, creating the dir + overwriting any previous. */
    fun put(context: Context, beanId: String, bytes: ByteArray) {
        val f = beanImageFile(context, beanId)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    /** Drop a bean's photo, if any. */
    fun delete(context: Context, beanId: String) {
        beanImageFile(context, beanId).delete()
    }
}
