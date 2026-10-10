package io.github.norlive.remotecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelLayoutTest {
    @Test fun offsetUsesAvailableTopLeftRange() {
        assertEquals(
            PanelOffset(80f, 150f),
            panelOffset(PanelPosition(0.25f, 0.75f), 400f, 300f, 80f, 100f),
        )
    }

    @Test fun dragConvertsPixelDeltaIntoNormalizedPosition() {
        assertEquals(
            PanelPosition(0.35f, 0.5f),
            movePanelItem(PanelPosition(0.25f, 0.75f), 32f, -50f, 400f, 300f, 80f, 100f),
        )
    }

    @Test fun dragAndOffsetStayInsideAvailableCanvas() {
        assertEquals(
            PanelPosition(1f, 0f),
            movePanelItem(PanelPosition(0.9f, 0.1f), 1000f, -1000f, 400f, 300f, 80f, 100f),
        )
        assertEquals(
            PanelOffset(320f, 0f),
            panelOffset(PanelPosition(2f, -1f), 400f, 300f, 80f, 100f),
        )
    }

    @Test fun itemAtLeastAsLargeAsCanvasCannotMoveOnThatAxis() {
        assertEquals(
            PanelOffset(0f, 150f),
            panelOffset(PanelPosition(1f, 0.75f), 80f, 300f, 80f, 100f),
        )
        assertEquals(
            PanelPosition(0f, 0.5f),
            movePanelItem(PanelPosition(0.75f, 0.75f), 40f, -50f, 60f, 300f, 80f, 100f),
        )
    }

    @Test fun movementOutsideEditingIsIgnored() {
        val session = LayoutEditSession(mapOf("leftStick" to PanelPosition(0.2f, 0.4f)))
        session.move("leftStick", PanelPosition(0.8f, 0.9f))
        assertEquals(PanelPosition(0.2f, 0.4f), session.positions["leftStick"])
        assertFalse(session.editing)
    }

    @Test fun draftMovementDoesNotChangeSavedPositionUntilSave() {
        val session = LayoutEditSession(mapOf("leftStick" to PanelPosition(0.2f, 0.4f)))
        session.begin()
        session.move("leftStick", PanelPosition(0.8f, 0.9f))
        assertTrue(session.editing)
        assertEquals(PanelPosition(0.2f, 0.4f), session.saved["leftStick"])
        assertEquals(PanelPosition(0.8f, 0.9f), session.draft["leftStick"])
        assertEquals(PanelPosition(0.8f, 0.9f), session.positions["leftStick"])
    }

    @Test fun cancelDiscardsDraftAndSaveCommitsIt() {
        val session = LayoutEditSession(mapOf("leftStick" to PanelPosition(0.2f, 0.4f)))
        session.begin()
        session.move("leftStick", PanelPosition(0.8f, 0.9f))
        session.cancel()
        assertFalse(session.editing)
        assertEquals(PanelPosition(0.2f, 0.4f), session.positions["leftStick"])

        session.begin()
        session.move("leftStick", PanelPosition(0.6f, 0.7f))
        session.save()
        assertFalse(session.editing)
        assertEquals(PanelPosition(0.6f, 0.7f), session.saved["leftStick"])
        assertEquals(PanelPosition(0.6f, 0.7f), session.positions["leftStick"])
    }

    @Test fun restoreDefaultsAffectsOnlyDraftUntilSaved() {
        val initial = mapOf("leftStick" to PanelPosition(0.2f, 0.4f))
        val session = LayoutEditSession(initial)
        session.begin()
        session.restoreDefaults()
        assertTrue(session.positions.isEmpty())
        assertEquals(initial, session.saved)
        session.cancel()
        assertEquals(initial, session.positions)

        session.begin()
        session.restoreDefaults()
        session.save()
        assertTrue(session.saved.isEmpty())
    }

    @Test fun moveClampsStoredPositionToUnitRange() {
        val session = LayoutEditSession()
        session.begin()
        session.move("key0", PanelPosition(-0.2f, 1.3f))
        assertEquals(PanelPosition(0f, 1f), session.positions["key0"])
    }
    @Test fun sizeDraftSaveCancelAndDefaultsAreIndependentOfPositions() {
        val session=LayoutEditSession(mapOf("leftPad" to PanelPosition(.2f,.3f)),mapOf("leftPad" to .8f))
        session.resize("leftPad",1.2f);assertEquals(.8f,session.scales["leftPad"])
        session.begin();session.resize("leftPad",.6f)
        assertEquals(.6f,session.scales["leftPad"]);assertEquals(.8f,session.savedScales["leftPad"])
        session.cancel();assertEquals(.8f,session.scales["leftPad"])
        session.begin();session.resize("leftPad",1.2f);session.save()
        assertEquals(1.2f,session.savedScales["leftPad"])
        session.begin();session.restoreDefaults();assertTrue(session.scales.isEmpty())
        session.cancel();assertEquals(1.2f,session.scales["leftPad"])
        assertEquals(PanelPosition(.2f,.3f),session.positions["leftPad"])
        session.begin();session.resize("leftPad",9f);assertEquals(1.5f,session.scales["leftPad"])
    }
}
