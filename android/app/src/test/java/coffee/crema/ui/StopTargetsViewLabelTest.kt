package coffee.crema.ui

import coffee.crema.core.CremaBridge
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The weight row's note is the core's: the shell shows it as given. */
class StopTargetsViewLabelTest {
    @Test
    fun theLabelIsTheCoresNote() {
        val note = "Scale lost before the cup had weight. Stop this shot by hand."
        assertEquals(note, StopTargetsView(weightBlocked = "scale-lost", weightBlockedNote = note).weightBlockedLabel)
        assertEquals(null, StopTargetsView().weightBlockedLabel)
    }

    @Test
    fun theCoresProjectionCarriesTheNote() {
        val bridge = CremaBridge()
        bridge.setProfileTargetWeight(36f)
        val o = coreJson.parseToJsonElement(bridge.stopTargetsProjection()).jsonObject
        assertEquals("no-scale", o["weightBlocked"]?.jsonPrimitive?.contentOrNull)
        assertEquals("no scale", o["weightBlockedNote"]?.jsonPrimitive?.contentOrNull)
    }
}
