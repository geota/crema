package coffee.crema.history

import coffee.crema.brew.CustomMethods
import coffee.crema.core.BrewMethodStyle
import coffee.crema.core.CustomBrewMethod
import coffee.crema.ui.beans.shotRowSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one naming rule every shot list applies (#165): profile, else recipe,
 * else method label. The bean detail's "Shots" rows used to read only the
 * profile name, so every pourover / custom-method brew showed as "—".
 */
class ShotTitleTest {
    private val orb = CustomBrewMethod(
        id = "custom:orb",
        label = "ORB",
        style = BrewMethodStyle.Pressure,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun shot(
        profileName: String? = null,
        recipeName: String? = null,
        brewMethod: String? = null,
        brewMethodLabel: String? = null,
    ) = StoredShot(
        id = "s",
        completedAtMs = 1,
        durationMs = 30_000,
        profileName = profileName,
        recipeName = recipeName,
        brewMethod = brewMethod,
        brewMethodLabel = brewMethodLabel,
    )

    @After fun reset() {
        CustomMethods.all = emptyList()
        CustomMethods.snapshotLabels = emptyMap()
    }

    @Test fun machineEspressoIsNamedByItsProfile() {
        assertEquals("Best of Decent", shotTitle(shot(profileName = "Best of Decent")))
        // Same words as the web's shotTitle (cohort review 2026-10-25, 06 #14).
        assertEquals("Untitled shot", shotTitle(shot()))
        assertEquals("Untitled shot", shotTitle(shot(profileName = "  ")))
    }

    @Test fun guidedBrewsCarryTheirRecipeName() {
        assertEquals("V60 recipe", shotTitle(shot(brewMethod = "pourover", recipeName = "V60 recipe")))
        CustomMethods.all = listOf(orb)
        assertEquals("ORB recipe", shotTitle(shot(brewMethod = "custom:orb", recipeName = "ORB recipe")))
        assertEquals("P", shotTitle(shot(profileName = "P", brewMethod = "pourover", recipeName = "R")))
    }

    @Test fun loggedBrewsWithoutARecipeFallBackToTheMethodLabel() {
        assertEquals("V60 / pourover", shotTitle(shot(brewMethod = "pourover")))
        assertEquals("Espresso", shotTitle(shot(brewMethod = "espresso")))
        CustomMethods.all = listOf(orb)
        assertEquals("ORB", shotTitle(shot(brewMethod = "custom:orb")))
        CustomMethods.all = emptyList()
        CustomMethods.snapshotLabels = mapOf("custom:lost" to "Mine")
        assertEquals("Mine", shotTitle(shot(brewMethod = "custom:lost", brewMethodLabel = "Mine")))
    }

    @Test fun theBeanDetailRowUsesTheSameTitle() {
        assertEquals("V60 recipe", shotRowSummary(shot(brewMethod = "pourover", recipeName = "V60 recipe")).title)
        assertEquals("V60 / pourover", shotRowSummary(shot(brewMethod = "pourover")).title)
    }
}
