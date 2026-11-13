package coffee.crema.ui

import coffee.crema.core.CremaBridge
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** A library profile whose yield is 36 g, as the core's desired profile. */
private const val PROFILE_36G =
    """{"id":"p","name":"p","source":"custom","dose":18,"yieldOut":36,"segments":[]}"""

/** The weight row's note is the core's: the shell shows it as given. */
class StopTargetsViewLabelTest {
    @Test
    fun theLabelIsTheCoresNote() {
        val note = "Scale stopped reporting. Stop this shot by hand."
        assertEquals(note, StopTargetsView(weightBlocked = "scale-lost", weightBlockedNote = note).weightBlockedLabel)
        assertEquals(null, StopTargetsView().weightBlockedLabel)
    }

    @Test
    fun theCoresProjectionCarriesTheNote() {
        val bridge = CremaBridge()
        bridge.setDesiredProfile(PROFILE_36G, null, 0uL)
        val o = coreJson.parseToJsonElement(bridge.stopTargetsProjection()).jsonObject
        assertEquals("no-scale", o["weightBlocked"]?.jsonPrimitive?.contentOrNull)
        assertEquals("no scale", o["weightBlockedNote"]?.jsonPrimitive?.contentOrNull)
    }
}
