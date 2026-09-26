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
}
