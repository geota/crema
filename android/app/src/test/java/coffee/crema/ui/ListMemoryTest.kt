package coffee.crema.ui

import androidx.compose.runtime.saveable.SaverScope
import coffee.crema.beans.BeanFacets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scroll / view-state holder behind issue #123. */
class ListMemoryTest {
    private val beans = listOf("b1", "b2", "b3", "b4", "b5", "b6")

    /** A phone Beans list: row 0 is the search field, bags follow. */
    private fun phoneIndex(rows: List<String>): (String) -> Int? = { k -> rows.indexOf(k).takeIf { it >= 0 }?.plus(1) }

    /** The tablet grid: bags from row 0. */
    private fun tabletIndex(rows: List<String>): (String) -> Int? = { k -> rows.indexOf(k).takeIf { it >= 0 } }

    @Test fun aListComesBackAsTheSameLiveState() {
        val m = ListMemory()
        val key = beansListKey(BeanFacets(status = "archived", roasterId = null), "freshest", false)
        val first = m.listState("phone", key, phoneIndex(beans))
        // The list scrolls, then leaves composition (detail swap / editor route).
        m.record(key, ListMemory.Anchor("b4", 4, 12, writer = "list:phone:$key"))
        val again = m.listState("phone", key, phoneIndex(beans))
        assertSame("the live state (with Compose's own key tracking) survives", first, again)
    }

    @Test fun eachFilterKeepsItsOwnPlace() {
        val m = ListMemory()
        val archived = m.listState("phone", beansListKey(BeanFacets(status = "archived", roasterId = null), "freshest", false), phoneIndex(beans))
        val all = m.listState("phone", beansListKey(BeanFacets(status = "all", roasterId = null), "freshest", false), phoneIndex(beans))
        assertNotSame(archived, all)
    }

    @Test fun theOtherShellSeedsFromTheAnchorItemNotTheRawIndex() {
        val m = ListMemory()
        val key = beansListKey(BeanFacets(status = "all", roasterId = null), "freshest", false)
        m.listState("phone", key, phoneIndex(beans))
        // Phone: b4 is the first visible row — LazyColumn index 4 (search is 0).
        m.record(key, ListMemory.Anchor("b4", 4, 30, writer = "list:phone:$key"))
        // Rotate across 840dp: the tablet grid has no search row, b4 is index 3.
        val grid = m.gridState("tablet", key, tabletIndex(beans))
        assertEquals(3, grid.firstVisibleItemIndex)
        assertEquals(30, grid.firstVisibleItemScrollOffset)
    }

    @Test fun aStaleLiveStateIsReseededAfterTheOtherShellScrolled() {
        val m = ListMemory()
        val key = historyListKey("all", null, null, null, "date", true)
        val phone = m.listState("phone", key, phoneIndex(beans))
        m.record(key, ListMemory.Anchor("b2", 2, 0, writer = "list:phone:$key"))
        // The tablet takes over and scrolls further.
        m.listState("tablet", key, tabletIndex(beans))
        m.record(key, ListMemory.Anchor("b5", 4, 0, writer = "list:tablet:$key"))
        // Back on the phone the old live state is stale: re-seed at b5.
        val phoneAgain = m.listState("phone", key, phoneIndex(beans))
        assertNotSame(phone, phoneAgain)
        assertEquals(5, phoneAgain.firstVisibleItemIndex)
    }

    @Test fun aRemovedAnchorLandsNextToWhereItWas() {
        val m = ListMemory()
        val key = beansListKey(BeanFacets(status = "archived", roasterId = null), "freshest", false)
        m.record(key, ListMemory.Anchor("b4", 4, 40, writer = ""))
        // b4 was restored to active from its detail: it left the Archived list.
        val remaining = beans - "b4"
        assertEquals(4 to 0, m.resolve(key, phoneIndex(remaining)))
        // …and the row now at index 4 is the bag that followed it.
        assertEquals("b5", remaining[4 - 1])
    }

    @Test fun aMovedAnchorIsFoundByKey() {
        val m = ListMemory()
        val key = roastersListKey(false)
        m.record(key, ListMemory.Anchor("b4", 4, 8, writer = ""))
        val reordered = listOf("b6", "b4", "b1", "b2", "b3", "b5")
        assertEquals(2 to 8, m.resolve(key, phoneIndex(reordered)))
    }

    @Test fun aHeaderAnchorKeepsItsIndexAndOffset() {
        val m = ListMemory()
        m.record("k", ListMemory.Anchor(null, 0, 120, writer = ""))
        assertEquals(0 to 120, m.resolve("k") { null })
        assertEquals(0 to 0, m.resolve("unknown") { 3 })
    }

    @Test fun viewValuesDefaultSetAndClear() {
        val m = ListMemory()
        val query = m.string("beans/query", "")
        val desc = m.bool("history/sortDesc", true)
        val bean = m.optString("history/bean")
        assertEquals("", query.value)
        assertTrue(desc.value)
        assertNull(bean.value)
        query.value = "ethiopia"
        desc.value = false
        bean.value = "bag-1"
        // A second handle (the other shell's screen) sees the same values.
        assertEquals("ethiopia", m.string("beans/query", "").value)
        assertEquals(false, m.bool("history/sortDesc", true).value)
        assertEquals("bag-1", m.optString("history/bean").value)
        bean.value = null
        assertNull(m.optString("history/bean").value)
    }

    @Test fun saverRoundTripsAnchorsValuesAndScrolls() {
        val m = ListMemory()
        val key = beansListKey(BeanFacets(status = "archived", roasterId = "r1"), "name", true)
        m.listState("phone", key, phoneIndex(beans))
        m.record(key, ListMemory.Anchor("b3", 3, 16, writer = "list:phone:$key"))
        m.record("header", ListMemory.Anchor(null, 0, 50, writer = "x"))
        m.string("beans/query", "").value = "kenya\twith tab"
        m.bool("beans/sortDesc", false).value = true
        m.scrollState("phone", "settings/sections")
        val saved = with(ListMemory.Saver) { SaverScope { true }.save(m) }!!
        val back = ListMemory.Saver.restore(saved)!!
        assertEquals(ListMemory.Anchor("b3", 3, 16), back.anchor(key))
        assertEquals(ListMemory.Anchor(null, 0, 50), back.anchor("header"))
        assertEquals("kenya\twith tab", back.string("beans/query", "").value)
        assertTrue(back.bool("beans/sortDesc", false).value)
        // After process death the list re-seeds on the same bag.
        val restored = back.listState("phone", key, phoneIndex(listOf("b9") + beans))
        assertEquals(4, restored.firstVisibleItemIndex)
        assertEquals(16, restored.firstVisibleItemScrollOffset)
        assertEquals(0, back.scrollState("phone", "settings/sections").value)
    }

    @Test fun everyFacetAxisGetsItsOwnPlace() {
        // #124's axes compose; each combination is its own list (and place).
        val base = BeanFacets(status = "archived", roast = "light")
        val keys = setOf(
            beansListKey(base, "freshest", false),
            beansListKey(base.copy(includeArchived = true), "freshest", false),
            beansListKey(base.copy(roast = "medium"), "freshest", false),
            beansListKey(base.copy(tags = setOf("comp")), "freshest", false),
            beansListKey(base.copy(roasterId = "r1"), "freshest", false),
            beansListKey(base, "name", false),
        )
        assertEquals(6, keys.size)
        // Tag order doesn't make a different list.
        assertEquals(
            beansListKey(base.copy(tags = setOf("a", "b")), "x", true),
            beansListKey(base.copy(tags = linkedSetOf("b", "a")), "x", true),
        )
        assertTrue(profilesListKey("hidden", "light", "name", false, false) != profilesListKey("hidden", null, "name", false, false))
    }
}
