package coffee.crema.visualizer

import java.util.concurrent.ConcurrentHashMap

/**
 * The bags whose first Visualizer upload (the create) is running (#488
 * follow-up 41). A bag deleted meanwhile has no Visualizer id yet, but the
 * create may still land: the delete then keeps a tombstone, so the core's
 * pull reconcile (`reconcile_pulled_bags`) matches the new remote bag to it
 * by signature and never adds it back. Shared by the bean sync and the
 * library on one device; nothing is persisted.
 */
object BagFirstUploads {
    private val ids: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Bag [id]'s create starts. */
    fun start(id: String) {
        ids += id
    }

    /** Bag [id]'s create ended (landed or failed). */
    fun done(id: String) {
        ids -= id
    }

    /** Whether bag [id]'s create is running now. */
    fun running(id: String): Boolean = id in ids
}
