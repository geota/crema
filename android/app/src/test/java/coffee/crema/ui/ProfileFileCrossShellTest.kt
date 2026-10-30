package coffee.crema.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import coffee.crema.MainSources
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F12: profile files on Android go through the core. The fixtures live in
 * `core/de1-domain/tests/fixtures/profiles/`: `web-profile.json` and
 * `web-profiles.jsonl` are web's exports from before F12 (its single export
 * and "Export all"), `crema-profile.json` / `crema-profiles.jsonl` the core's
 * export of Android's old "Export all" set (regenerate them from the core:
 * `CREMA_REGEN_PROFILE_FIXTURES=1 cargo test -p de1-domain profile_file`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ProfileFileCrossShellTest {
    private val store = ViewModelStore()
    private val fixtures = File("../../core/de1-domain/tests/fixtures")

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private fun fixture(name: String) = File(fixtures, name).readText()

    private fun build(): MainViewModel {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    /** Import [text] the way the Profiles screen does (a picked document). */
    private fun pickAndImport(vm: MainViewModel, name: String, text: String): String {
        val file = File.createTempFile("f12-", "-$name").apply { writeText(text); deleteOnExit() }
        val before = vm.ui.value.userMessages.size
        vm.importProfiles(Uri.fromFile(file))
        VmHarness.idleUntil("the import of $name") { vm.ui.value.userMessages.size > before }
        return vm.ui.value.userMessages.last()
    }

    private fun customs(vm: MainViewModel) = vm.ui.value.profiles.filter { it.source == "custom" }

    /** The VM's library controller, found by type. */
    private fun libraryOf(vm: MainViewModel): LibraryController {
        val field = MainViewModel::class.java.declaredFields.single { it.type == LibraryController::class.java }
        field.isAccessible = true
        return field.get(vm) as LibraryController
    }

    @Test
    fun `f12_6 web's exports import on Android`() {
        val vm = build()
        assertEquals("Imported 1 profile(s)", pickAndImport(vm, "web-profile.json", fixture("profiles/web-profile.json")))
        assertEquals("Imported 3 profile(s)", pickAndImport(vm, "web-profiles.jsonl", fixture("profiles/web-profiles.jsonl")))
        val names = customs(vm).map { it.name }.sorted()
        assertEquals(listOf("7g basket", "7g basket", "A-Flow / default-dark", "Blooming Allongé"), names)
        for (p in customs(vm)) {
            assertFalse("Built-in" in p.tags, p.name)
            assertEquals(p.segments.first().temp, p.brewTemp, p.name)
            assertFalse(p.id.startsWith("profile:"), p.id)
        }
    }

    @Test
    fun `f12_6 Android's export of a set is the core's file, byte for byte`() {
        val vm = build()
        assertEquals("Imported 4 profile(s)", pickAndImport(vm, "android-profiles.json", fixture("profiles/android-profiles.json")))
        val byName = customs(vm).associateBy { it.name }
        val ids = listOf("My 7g", "7g basket", "Blooming Allongé", "A-Flow / default-dark").map { byName.getValue(it).id }
        val library = libraryOf(vm)
        assertEquals(fixture("profiles/crema-profiles.jsonl"), library.profilesFile(ids) { error(it) })
        assertEquals(fixture("profiles/crema-profile.json"), library.profilesFile(ids.take(1)) { error(it) })
        // "Export all" is the same file over the whole library: every line a v2 profile.
        val all = vm.allProfilesFile()!!.lines().filter { it.isNotBlank() }
        assertEquals(vm.ui.value.profiles.size, all.size)
        assertTrue(all.all { it.startsWith("{\"title\":") })
    }

    /** The text the share sheet was handed by the last share. */
    private fun sharedText(): String {
        val chooser = org.robolectric.Shadows.shadowOf(VmHarness.app()).nextStartedActivity
            ?: error("nothing was shared")
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        return send.getStringExtra(android.content.Intent.EXTRA_TEXT)!!
    }

    @Test
    fun `f12_6 sharing one profile hands out the core's file`() {
        val vm = build()
        pickAndImport(vm, "android-profiles.json", fixture("profiles/android-profiles.json"))
        val mine = customs(vm).single { it.name == "My 7g" }
        vm.exportProfile(mine.id)
        VmHarness.idle()
        assertEquals(fixture("profiles/crema-profile.json"), sharedText())
    }

    @Test
    fun `f12_1 a de1app tcl and a backup import on Android too`() {
        val vm = build()
        assertEquals("Imported 1 profile(s)", pickAndImport(vm, "default.tcl", fixture("default.tcl")))
        assertEquals(
            "Imported 1 profile(s)",
            pickAndImport(vm, "web-backup.crema.jsonl", fixture("backup/web-backup.crema.jsonl")),
        )
    }

    @Test
    fun `f12_3 an object that is not a profile is refused, not adopted`() {
        val vm = build()
        val before = customs(vm).size
        val message = pickAndImport(vm, "groceries.json", """{"name":"Groceries","items":["milk"]}""")
        assertEquals("Couldn’t import profile — not a profile (it has neither steps nor segments)", message)
        assertEquals(before, customs(vm).size)
        val lines = fixture("profiles/web-profiles.jsonl").lines().toMutableList().apply { add(1, """{"name":"x"}""") }
        assertEquals(
            "Imported 3 of 4 — item 2: not a profile (it has neither steps nor segments)",
            pickAndImport(vm, "mixed.jsonl", lines.joinToString("\n")),
        )
    }

    @Test
    fun `f12_7 profile files go through the core only`() {
        val sources = MainSources.kotlinFiles()
        val direct = sources.filter { (_, text) ->
            listOf("importV2JsonProfile", "importLegacyTclProfile", "exportV2JsonProfile", "adoptProfileJson").any { it in text }
        }.map { it.first }
        assertEquals(emptyList(), direct)
        val readers = sources.filter { (_, text) -> "importProfileFile(" in text }.map { it.first }
        assertEquals(listOf("coffee/crema/ui/LibraryController.kt"), readers)
        val writers = sources.filter { (_, text) -> "exportProfileFile(" in text }.map { it.first }
        assertEquals(listOf("coffee/crema/ui/LibraryController.kt"), writers)
    }
}
