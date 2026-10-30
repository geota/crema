package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanOrigin
import coffee.crema.core.Roaster
import coffee.crema.core.importCremaJsonl
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F11b: Android's bean export is the core's Crema JSONL and re-imports on both
 * shells. These run on the host build of the core. `android-beans.crema.jsonl`
 * in `core/de1-domain/tests/fixtures/beans/` was written by [beanLibraryJsonl]
 * from [beans] / [roasters] below and is what web's import test reads
 * (`web/src/lib/bean/android-export.vitest.ts`). Regenerate with
 * `CREMA_REGEN_BEAN_FIXTURES=1 ./gradlew :app:testDebugUnitTest --tests
 * coffee.crema.beans.BeanInterchangeTest`.
 */
class BeanInterchangeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fixture = File("../../core/de1-domain/tests/fixtures/beans/android-beans.crema.jsonl")

    private val roasters = listOf(newRoaster("Onyx", 1_790_000_000_000).copy(id = "roaster:android-1", city = "Rogers"))
    private val beans = listOf(
        newBean("Halo Hambela", "roaster:android-1", 2, "2026-09-01", 1_790_000_000_000).copy(
            id = "bean:android-1",
            frozenOn = "2026-09-05",
            defrostedOn = "2026-09-20",
            tags = listOf("comp", "washed"),
            bagSize = 250f,
            remaining = 180f,
            origin = BeanOrigin(country = "Ethiopia", processing = "Washed"),
            visualizerId = "vb-1",
            canonicalCoffeeBagId = "cat-bag-1",
            linkedProfileId = "builtin:londinium",
            cost = 21.5f,
            metadata = buildJsonObject { put("note", "kept") },
        ),
        newBean("House Blend", null, 7, null, 1_790_000_000_000).copy(id = "bean:android-2", favourite = true, tags = emptyList()),
    )

    /** The core's import plan for [text], decoded the way the import path does. */
    private fun imported(text: String): Pair<List<Bean>, List<Roaster>> {
        val plan = json.parseToJsonElement(importCremaJsonl(text)).jsonObject
        fun <T> list(key: String, ser: kotlinx.serialization.KSerializer<T>) =
            json.decodeFromJsonElement(ListSerializer(ser), plan[key] ?: JsonArray(emptyList()))
        return list("beans", Bean.serializer()) to list("roasters", Roaster.serializer())
    }

    @Test
    fun `regenerates the Android bean fixture when asked`() {
        if (System.getenv("CREMA_REGEN_BEAN_FIXTURES") != "1") return
        fixture.parentFile.mkdirs()
        fixture.writeText(beanLibraryJsonl(beans, roasters, 1_790_000_000_000, "fixture"))
    }

    @Test
    fun `the bean export is Crema JSONL and re-imports on Android losslessly`() {
        val text = beanLibraryJsonl(beans, roasters, 1_790_000_000_000, "test")
        assertTrue(isCremaLibraryJsonl(text))
        val (wireBeans, wireRoasters) = imported(text)
        assertEquals(beans, wireBeans)
        assertEquals(roasters, wireRoasters)
        val merge = mergeBeanImport(wireBeans, wireRoasters, emptyList(), emptyList())
        assertEquals(beans, merge.newBeans)
        assertEquals(roasters, merge.newRoasters)
    }

    @Test
    fun `the committed Android bean export re-imports on Android`() {
        val text = fixture.readText()
        assertTrue(isCremaLibraryJsonl(text))
        val (wireBeans, wireRoasters) = imported(text)
        assertEquals(listOf("bean:android-1", "bean:android-2"), wireBeans.map { it.id })
        assertEquals(listOf("comp", "washed"), wireBeans[0].tags)
        assertEquals("2026-09-20", wireBeans[0].defrostedOn)
        assertEquals(listOf("Onyx"), wireRoasters.map { it.name })
    }

    @Test
    fun `re-importing an export into the same library adds nothing`() {
        val (wireBeans, wireRoasters) = imported(beanLibraryJsonl(beans, roasters, 1, "test"))
        val renamed = beans.map { it.copy(name = it.name + " (edited)") }
        val roasterRenamed = roasters.map { it.copy(name = "Onyx Coffee Lab") }
        val merge = mergeBeanImport(wireBeans, wireRoasters, renamed, roasterRenamed)
        assertTrue(merge.newBeans.isEmpty())
        assertTrue(merge.newRoasters.isEmpty())
        assertEquals(2, merge.skipped)
        assertEquals(mapOf("bean:android-1" to "bean:android-1", "bean:android-2" to "bean:android-2"), merge.beanIdRemap)
    }

    @Test
    fun `only a crema v1 header marks a Crema bean export`() {
        assertTrue(isCremaLibraryJsonl("\n{\"kind\":\"crema/v1\",\"exportedAt\":1}\n{\"kind\":\"bean\"}"))
        assertFalse(isCremaLibraryJsonl("{\"kind\":\"crema-backup/v1\"}"))
        assertFalse(isCremaLibraryJsonl("{\"BEANS\":[],\"BREWS\":[]}"))
        assertFalse(isCremaLibraryJsonl("{\"beans\":[],\"roasters\":[],\"activeBeanId\":null}"))
        assertFalse(isCremaLibraryJsonl(""))
    }
}
