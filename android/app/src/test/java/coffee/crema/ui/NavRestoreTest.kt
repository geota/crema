package coffee.crema.ui

import coffee.crema.ui.NavRestore.PHONE_ROUTES
import coffee.crema.ui.NavRestore.TABLET_ROUTES
import org.junit.Assert.assertEquals
import org.junit.Test

class NavRestoreTest {
    @Test fun brewAndUnknownNeedNoSteps() {
        assertEquals(emptyList<String>(), NavRestore.steps("brew", PHONE_ROUTES))
        assertEquals(emptyList<String>(), NavRestore.steps(null, TABLET_ROUTES))
        assertEquals(emptyList<String>(), NavRestore.steps("nope", TABLET_ROUTES))
    }

    @Test fun tabsAreOneStepOnBothHosts() {
        for (tab in listOf("scale", "profiles", "beans", "history", "settings")) {
            assertEquals(listOf(tab), NavRestore.steps(tab, PHONE_ROUTES))
            assertEquals(listOf(tab), NavRestore.steps(tab, TABLET_ROUTES))
        }
    }

    @Test fun editorsGoThroughTheirOwningTab() {
        assertEquals(listOf("beans", "bean-edit"), NavRestore.steps("bean-edit", TABLET_ROUTES))
        assertEquals(listOf("profiles", "profile-edit"), NavRestore.steps("profile-edit", PHONE_ROUTES))
        assertEquals(listOf("settings", "debug"), NavRestore.steps("debug", TABLET_ROUTES))
        assertEquals(listOf("beans", "roaster-edit"), NavRestore.steps("roaster-edit", PHONE_ROUTES))
    }

    @Test fun missingEditorFallsBackToOwningTab() {
        assertEquals(listOf("beans"), NavRestore.steps("roaster-edit", TABLET_ROUTES))
    }

    // ── Brew Log form (issue #10) ────────────────────────────────────────

    @Test fun phoneLogBrewOpensTheOwningTabOnTablet() {
        // Opened from History or from a bean's detail: the tablet lands on
        // that tab, where the side sheet re-opens from the VM-held draft.
        for (owner in listOf("history", "beans")) {
            val route = NavRestore.restoreRoute("log-brew", owner, phone = false)
            assertEquals("log-brew", route)
            assertEquals(listOf(owner), NavRestore.steps(route, TABLET_ROUTES, NavRestore.owners(owner)))
        }
    }

    @Test fun tabletSheetBecomesThePushedRouteOnPhone() {
        for (owner in listOf("history", "beans")) {
            val route = NavRestore.restoreRoute(owner, owner, phone = true)
            assertEquals("log-brew", route)
            assertEquals(listOf(owner, "log-brew"), NavRestore.steps(route, PHONE_ROUTES, NavRestore.owners(owner)))
        }
    }

    @Test fun anOpenFormOnAnotherTabDoesNotHijackTheRoute() {
        assertEquals("profiles", NavRestore.restoreRoute("profiles", "history", phone = true))
        assertEquals("history", NavRestore.restoreRoute("history", null, phone = true))
        assertEquals("beans", NavRestore.restoreRoute("beans", "beans", phone = false))
    }

    @Test fun aStaleLogBrewRouteWithNoFormFallsBackToHistory() {
        assertEquals("history", NavRestore.restoreRoute("log-brew", null, phone = true))
        assertEquals("history", NavRestore.restoreRoute("log-brew", null, phone = false))
        // And the static fallback owner still resolves without a draft.
        assertEquals(listOf("history"), NavRestore.steps("log-brew", TABLET_ROUTES))
    }

    // ── Guided-brew recipe editor (issue #10 Phase 2) ────────────────────

    @Test fun phoneRecipeEditOpensTheSheetOnTheOwningTabOnTablet() {
        // Opened from Scale's Brew setup or from Profiles: the tablet lands on
        // that tab, where the side sheet re-opens from the VM-held draft.
        for (owner in listOf("scale", "profiles")) {
            val route = NavRestore.restoreRoute("recipe-edit", null, phone = false, recipeEditOwner = owner)
            assertEquals("recipe-edit", route)
            assertEquals(
                listOf(owner),
                NavRestore.steps(route, TABLET_ROUTES, NavRestore.owners(null, recipeEditOwner = owner)),
            )
        }
    }

    @Test fun tabletRecipeSheetBecomesThePushedRouteOnPhone() {
        for (owner in listOf("scale", "profiles")) {
            val route = NavRestore.restoreRoute(owner, null, phone = true, recipeEditOwner = owner)
            assertEquals("recipe-edit", route)
            assertEquals(
                listOf(owner, "recipe-edit"),
                NavRestore.steps(route, PHONE_ROUTES, NavRestore.owners(null, recipeEditOwner = owner)),
            )
        }
    }

    @Test fun guidedLogBrewFromScaleFollowsTheSameHandOff() {
        // The finished session's "Save brew…" opens the log form owned by Scale.
        assertEquals("log-brew", NavRestore.restoreRoute("scale", "scale", phone = true))
        assertEquals(listOf("scale"), NavRestore.steps("log-brew", TABLET_ROUTES, NavRestore.owners("scale")))
    }

    @Test fun anOpenRecipeEditorOnAnotherTabDoesNotHijackTheRoute() {
        assertEquals("history", NavRestore.restoreRoute("history", null, phone = true, recipeEditOwner = "scale"))
        assertEquals("scale", NavRestore.restoreRoute("scale", null, phone = false, recipeEditOwner = "scale"))
    }

    @Test fun aStaleRecipeEditRouteFallsBackToProfiles() {
        assertEquals("profiles", NavRestore.restoreRoute("recipe-edit", null, phone = true))
        assertEquals(listOf("profiles"), NavRestore.steps("recipe-edit", TABLET_ROUTES))
    }
}
