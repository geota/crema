package coffee.crema.ui.screens

import coffee.crema.MainSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-34: no settings or Quick Controls screen checks the device's role. What
 * a row allows comes from `availability()` (through `MainUiState.gate`); the
 * one exception is the Multi-device and Paired devices blocks, which are about
 * the role itself.
 */
class NoRoleChecksInScreensTest {

    /** The settings and Quick Controls screens, and the rows they share. */
    private val screens = listOf(
        "coffee/crema/ui/screens/SettingsScreen.kt",
        "coffee/crema/ui/phone/PhoneSettingsScreen.kt",
        "coffee/crema/ui/screens/QuickControlsSheet.kt",
        "coffee/crema/ui/phone/PhoneBrewSheets.kt",
        "coffee/crema/ui/screens/BrewScreen.kt",
        "coffee/crema/ui/phone/PhoneBrewScreen.kt",
        "coffee/crema/ui/screens/ScaleScreen.kt",
        "coffee/crema/ui/phone/PhoneScaleScreen.kt",
        "coffee/crema/ui/components/SettingsRowComponents.kt",
        "coffee/crema/ui/components/SleepSettingsRows.kt",
        "coffee/crema/ui/components/SettingGate.kt",
    )

    /** Reads of the role, or of the remote state a row would gate on. */
    private val roleCheck = Regex("""\bproxyRole\b|"secondary"|\bmirrorViewOnly\b|\bmirrorReconnecting\b|\.mirror\b""")

    /** The settings groups that are about the role. */
    private val allowedGroups = listOf("Multi-device", "Paired devices")

    @Test
    fun `settings and quick controls rows don't check the role`() {
        val files = MainSources.kotlinFiles().toMap()
        val found = screens.flatMap { path ->
            val text = files[path] ?: error("$path is gone: update this list")
            roleChecks(text).map { "$path:$it" }
        }
        assertEquals(emptyList(), found, "gate rows with MainUiState.gate(setting) (availability), not the role")
    }

    @Test
    fun `the role blocks are where the allowlist expects them`() {
        // The allowlist must not be vacuous: both shells still have the role
        // block, and its checks are found inside it.
        val files = MainSources.kotlinFiles().toMap()
        for (path in listOf("coffee/crema/ui/screens/SettingsScreen.kt", "coffee/crema/ui/phone/PhoneSettingsScreen.kt")) {
            val text = files.getValue(path)
            assertTrue(roleCheck.containsMatchIn(text), "$path has a role block")
            assertTrue(allowedGroups.all { "Group(\"$it" in text }, "$path names its role groups")
        }
    }

    /** The lines of [text] that check the role outside an allowed group. */
    private fun roleChecks(text: String): List<Int> {
        val out = mutableListOf<Int>()
        // Open settings groups: (title, brace depth inside the group).
        val groups = ArrayDeque<Pair<String, Int>>()
        var depth = 0
        val header = Regex("""(?:SetGroup|SettingsGroup)\("([^"]*)"""")
        text.lines().forEachIndexed { i, line ->
            val code = line.substringBefore("//")
            // A group header's first brace opens the group.
            var opening = header.find(code)?.groupValues?.get(1)
            if (roleCheck.containsMatchIn(code)) {
                val titles = groups.map { it.first } + listOfNotNull(opening)
                if (titles.none { t -> allowedGroups.any(t::startsWith) }) out += i + 1
            }
            for (c in code) {
                if (c == '{') {
                    depth++
                    opening?.let { groups.addLast(it to depth) }
                    opening = null
                } else if (c == '}') {
                    if (groups.lastOrNull()?.second == depth) groups.removeLast()
                    depth--
                }
            }
        }
        return out
    }
}
