package coffee.crema.ui

import coffee.crema.core.Bean
import coffee.crema.core.BrewRecipe
import coffee.crema.core.CustomBrewMethod
import coffee.crema.core.ShotBean
import coffee.crema.core.blankCremaProfile
import coffee.crema.history.StoredShot
import coffee.crema.maintenance.defaultMaintenanceState
import coffee.crema.profiles.brewDefaultsJson
import coffee.crema.settings.AppPrefs
import coffee.crema.visualizer.DEFAULT_VISUALIZER_SYNC_PREFS
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F11a: a backup from either shell restores on Android through the core's
 * `importBackupJsonl` (the host build of the core, the parser web uses).
 *
 * The fixtures live in `core/de1-domain/tests/fixtures/backup/`:
 * `web-backup.crema.jsonl` was written by web's `buildBackupJsonl` (see
 * `web/src/lib/backup/cross-shell.vitest.ts`) and `android-backup.crema.jsonl`
 * by [backupBundleJsonl] from [seed] below. They are committed artifacts, so an
 * old backup keeps restoring as both shells change. Regenerate with
 * `CREMA_REGEN_BACKUP_FIXTURES=1 ./gradlew :app:testDebugUnitTest --tests
 * coffee.crema.ui.BackupCrossShellTest` (and the web vitest for its file).
 */
class BackupCrossShellTest {
    private val json = coreJson
    private val fixtures = File("../../core/de1-domain/tests/fixtures/backup")

    private fun fixture(name: String) = File(fixtures, name).readText()

    /** The Android seed behind `android-backup.crema.jsonl`. */
    private fun seed(): BackupSnapshot {
        val profile = json.parseToJsonElement(blankCremaProfile(brewDefaultsJson())).jsonObject.let {
            JsonObject(
                it + mapOf(
                    "id" to JsonPrimitive("profile:android-fixture"),
                    "name" to JsonPrimitive("Android Fixture Profile"),
                    "source" to JsonPrimitive("custom"),
                ),
            )
        }
        val bean = bag("bean:android-1", "Halo Hambela", "roaster:android-1").copy(
            roastedOn = "2026-09-01",
            frozenOn = "2026-09-05",
            defrostedOn = "2026-09-20",
            tags = listOf("comp"),
            bagSize = 250f,
            remaining = 180f,
        )
        val roaster = coffee.crema.beans.newRoaster("Onyx", 1_790_000_000_000).copy(id = "roaster:android-1")
        val shot = StoredShot(
            id = "shot:android-1",
            completedAtMs = 1_790_000_000_000,
            durationMs = 28_000,
            yieldG = 36f,
            doseG = 18f,
            profileName = "Android Fixture Profile",
            bean = ShotBean(
                beanId = bean.id,
                name = bean.name,
                roasterName = "Onyx",
                roastedOn = "2026-09-01",
                roastLevel = 4u,
                tags = listOf("comp"),
                grinderSetting = "2.5",
                grinder = "Niche Zero",
            ),
            rating = 4,
            notes = "from Android",
            samples = listOf(sample(0, 2f), sample(1_000, 9f)),
            brewTempC = 93f,
        )
        val method = json.decodeFromString(
            CustomBrewMethod.serializer(),
            """{"id":"custom:android-orb","label":"ORB","style":"percolation","createdAt":1,"updatedAt":2}""",
        )
        val recipe = json.decodeFromString(
            BrewRecipe.serializer(),
            """{"id":"recipe:android-copy","name":"Android V60","method":"pourover","doseG":15.0,"waterG":250.0,""" +
                """"tempC":96.0,"steps":[],"notes":"","favourite":false,"createdAt":1,"updatedAt":1,"deletedAt":null,""" +
                """"credit":"Adapted from James Hoffmann","sourceUrl":null}""",
        )
        val state = MainUiState(
            beans = listOf(bean),
            roasters = listOf(roaster),
            history = listOf(shot),
            customMethods = listOf(method),
            lastRecipeByMethod = mapOf("pourover" to recipe.id),
            maintenance = defaultMaintenanceState(1_790_000_000_000),
        )
        return BackupSnapshot(
            customs = listOf(json.encodeToString(JsonObject.serializer(), profile)),
            state = state,
            pinned = setOf("builtin:londinium"),
            hidden = emptySet(),
            prefs = AppPrefs(tempUnit = "F", maxShotDurationS = 90f),
            visualizerPrefs = DEFAULT_VISUALIZER_SYNC_PREFS,
            userRecipes = listOf(recipe),
        )
    }

    private fun bag(id: String, name: String, roasterId: String): Bean =
        coffee.crema.beans.newBean(name, roasterId, 4, null, 1_790_000_000_000).copy(id = id)

    private fun sample(elapsedMs: Long, pressure: Float) = TelemetrySample(
        elapsedMs = elapsedMs, pressure = pressure, flow = 2f, headTemp = 92f, mixTemp = 90f,
        weight = 10f, weightFlow = 1.5f, dispensedVolume = 20f, resistance = null, resistanceWeight = null,
        setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1,
    )

    @Test
    fun `regenerates the Android fixture when asked`() {
        if (System.getenv("CREMA_REGEN_BACKUP_FIXTURES") != "1") return
        val jsonl = backupBundleJsonl(seed(), json, 1_790_000_000_000, "fixture", "Android fixture")
        File(fixtures, "android-backup.crema.jsonl").writeText(jsonl)
    }

    @Test
    fun `a web backup restores on Android`() {
        val parsed = assertNotNull(parseBackup(fixture("web-backup.crema.jsonl"), json, 0L))
        assertEquals(listOf("bean:web-1"), parsed.beans.map { it.id })
        val bean = parsed.beans.single()
        assertEquals("Gesha Village", bean.name)
        assertEquals("roaster:web-1", bean.roasterId)
        assertEquals("2026-09-05", bean.frozenOn)
        assertEquals("2026-09-20", bean.defrostedOn)
        assertEquals(listOf("Sey"), parsed.roasters.map { it.name })
        val shot = parsed.shots.single()
        assertEquals("shot:web-1", shot.id)
        assertEquals(18f, shot.doseG)
        assertEquals(36f, shot.yieldG)
        assertEquals("Gesha Village", shot.bean?.name)
        assertEquals("bean:web-1", shot.bean?.beanId)
        assertEquals(2, shot.samples.size)
        assertEquals(9f, shot.peakPressure)
        assertEquals(listOf("My Web Profile"), parsed.profiles.map { json.parseToJsonElement(it).jsonObject["name"].toString().trim('"') })
        assertEquals(listOf("ORB"), parsed.customMethods.map { it.label })
        assertEquals("Ultimate V60 (copy)", parsed.recipes.single().name)
        val common = assertNotNull(parsed.common)
        assertEquals("F", common.tempUnit)
        assertEquals(90f, common.maxShotDurationS)
        assertNotNull(parsed.maintenance)
        assertNotNull(parsed.visualizerPrefs)
    }

    @Test
    fun `the Android backup fixture restores on Android`() {
        val parsed = assertNotNull(parseBackup(fixture("android-backup.crema.jsonl"), json, 0L))
        assertAndroidSeed(parsed)
    }

    @Test
    fun `today's Android export restores on Android`() {
        val jsonl = backupBundleJsonl(seed(), json, 1_790_000_000_000, "test", "Android test")
        assertAndroidSeed(assertNotNull(parseBackup(jsonl, json, 0L)))
    }

    private fun assertAndroidSeed(parsed: ParsedBackup) {
        assertEquals(listOf("bean:android-1"), parsed.beans.map { it.id })
        assertEquals("2026-09-20", parsed.beans.single().defrostedOn)
        assertEquals(listOf("roaster:android-1"), parsed.roasters.map { it.id })
        val shot = parsed.shots.single()
        assertEquals("shot:android-1", shot.id)
        assertEquals("Halo Hambela", shot.bean?.name)
        assertEquals(2, shot.samples.size)
        assertEquals(93f, shot.brewTempC)
        assertEquals(1, parsed.profiles.size)
        assertTrue(parsed.profiles.single().contains("profile:android-fixture"))
        assertEquals(listOf("custom:android-orb"), parsed.customMethods.map { it.id })
        assertEquals(listOf("recipe:android-copy"), parsed.recipes.map { it.id })
        assertEquals("F", parsed.common?.tempUnit)
        assertEquals(90f, parsed.common?.maxShotDurationS)
    }
}
