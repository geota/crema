package coffee.crema.settings

import coffee.crema.MainSources
import coffee.crema.core.MaintenanceState
import coffee.crema.core.VisualizerSyncPrefs
import coffee.crema.decent.DecentState
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.valueParameters
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings registry ([Setting]) covers everything Crema stores or edits
 * (spec §5, §7.1): every stored field is tagged exactly once or excluded with
 * a reason, every public setter is someone's setter or excluded, and the
 * groups follow the rules.
 */
class SettingsRegistryTest {

    private fun names(serializer: KSerializer<*>): Set<String> = serializer.descriptor.elementNames.toSet()

    private val stores: Map<SettingStore, Pair<String, Set<String>>> = mapOf(
        SettingStore.APP_PREFS to ("AppPrefs" to names(AppPrefs.serializer())),
        SettingStore.MAINTENANCE to ("MaintenanceState" to names(MaintenanceState.serializer())),
        SettingStore.VISUALIZER to ("VisualizerSyncPrefs" to names(VisualizerSyncPrefs.serializer())),
        SettingStore.DECENT to ("DecentState" to names(DecentState.serializer())),
    )

    /** AC-12. */
    @Test
    fun `every stored setting is tagged exactly once`() {
        for ((store, pair) in stores) {
            val (storeName, fields) = pair
            val tagged = Setting.entries.filter { it.store == store }.mapNotNull { it.storeField }
            assertEquals(tagged.size, tagged.toSet().size, "$storeName fields tagged twice: ${tagged.groupBy { it }.filter { it.value.size > 1 }.keys}")
            val excluded = NOT_SETTINGS.keys.filter { it.startsWith("$storeName.") }.map { it.removePrefix("$storeName.") }.toSet()
            assertEquals(emptySet(), tagged.toSet() intersect excluded, "$storeName fields both tagged and excluded")
            assertEquals(fields, tagged.toSet() + excluded, "$storeName: untagged fields (left) or tags for no field (right)")
        }
        // The stores without a serializer carry no storeField.
        for (s in Setting.entries.filter { it.store !in stores }) assertEquals(null, s.storeField, "$s")
    }

    @Test
    fun `the groups and counts match the spec`() {
        assertEquals(88, Setting.entries.size)
        assertEquals(21, Setting.entries.count { it.group == SettingGroup.A_MACHINE })
        assertEquals(13, Setting.entries.count { it.group == SettingGroup.B_SESSION })
        assertEquals(54, Setting.entries.count { it.group == SettingGroup.C_PERSONAL })
        for (s in Setting.entries) {
            if (s.group == SettingGroup.C_PERSONAL) {
                assertEquals(OnRemote.LOCAL, s.onRemote, "$s")
                assertEquals(MirrorSource.NONE, s.mirroredVia, "$s")
                assertTrue(!s.adoptable, "$s")
            } else {
                assertTrue(s.onRemote == OnRemote.RELAYED || s.onRemote == OnRemote.READ_ONLY, "$s")
                assertTrue(s.mirroredVia != MirrorSource.NONE, "$s")
            }
            assertEquals(s.mirroredVia == MirrorSource.SNAPSHOT, s.snapshotField != null, "$s: snapshotField iff SNAPSHOT")
            if (s.adoptable) assertTrue(s.store in setOf(SettingStore.APP_PREFS, SettingStore.LIBRARY, SettingStore.MEMORY), "$s")
        }
        // Adoptable at T6: A1–A11 and B1–B13.
        assertEquals(24, Setting.entries.count { it.adoptable })
    }

    /** AC-14. */
    @Test
    fun `the snapshot carries every A and B setting and no C setting`() {
        val snapshotFields = names(ConfigSnapshot.serializer())
        val ab = Setting.entries.filter { it.group != SettingGroup.C_PERSONAL }
        val carried = ab.flatMap { it.snapshotFields }
        assertEquals(carried.size, carried.toSet().size, "a snapshot field carries two settings")
        assertEquals(emptySet(), SNAPSHOT_META intersect SNAPSHOT_DEPRECATED)
        assertEquals(snapshotFields, carried.toSet() + SNAPSHOT_META + SNAPSHOT_DEPRECATED, "untagged snapshot fields (left) or tags for no field (right)")
        for (s in ab) {
            assertTrue(s.mirroredVia in setOf(MirrorSource.SNAPSHOT, MirrorSource.SCALE_ECHO, MirrorSource.NONE_AVAILABLE), "$s")
        }
        for (s in Setting.entries.filter { it.group == SettingGroup.C_PERSONAL }) {
            assertEquals(emptyList(), s.snapshotFields, "$s is personal and never mirrored")
        }
    }

    /** AC-13: every public `set*` / `toggle*` on the VM, every controller,
     *  VisualizerSync, DecentSync and SharingController is tagged or excluded. */
    @Test
    fun `every public setter is tagged or excluded`() {
        val known = Setting.entries.flatMap { it.setters }.toSet() + NOT_SETTINGS.keys
        val found = owners().flatMap { k -> publicSetters(k) }.toSet()
        assertEquals(emptySet(), found - known, "public setters with no Setting and no NOT_SETTINGS entry")
        // And no tag names a setter that doesn't exist.
        val allPublic = owners().flatMap { k -> publicFunctions(k) }.toSet()
        val stale = Setting.entries.flatMap { it.setters } .filter { it !in allPublic }
        assertEquals(emptyList(), stale, "setters tagged on a Setting that don't exist")
    }

    /** The classes whose setters must be tagged: the VM, every `*Controller`
     *  in the app, VisualizerSync and DecentSync. */
    private fun owners(): List<KClass<*>> {
        val controllers = MainSources.kotlinFiles().flatMap { (path, text) ->
            val pkg = path.substringBeforeLast('/').replace('/', '.')
            Regex("""^class (\w+Controller)\b""", RegexOption.MULTILINE).findAll(text).map { "$pkg.${it.groupValues[1]}" }.toList()
        }
        val names = controllers + listOf("coffee.crema.ui.MainViewModel", "coffee.crema.visualizer.VisualizerSync", "coffee.crema.decent.DecentSync")
        return names.distinct().map { Class.forName(it).kotlin }
    }

    private fun signature(owner: KClass<*>, f: KFunction<*>): String =
        "${owner.simpleName}.${f.name}(${f.valueParameters.joinToString(", ") { p ->
            (p.type.classifier as? KClass<*>)?.simpleName + if (p.type.isMarkedNullable) "?" else ""
        }})"

    private fun publicFunctions(owner: KClass<*>): List<String> =
        owner.declaredMemberFunctions.filter { it.visibility == KVisibility.PUBLIC }.map { signature(owner, it) }

    private fun publicSetters(owner: KClass<*>): List<String> =
        owner.declaredMemberFunctions
            .filter { it.visibility == KVisibility.PUBLIC && Regex("^(set|toggle)[A-Z]").containsMatchIn(it.name) }
            .map { signature(owner, it) }
}
