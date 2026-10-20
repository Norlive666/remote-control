package io.github.norlive.remotecontrol

import org.junit.Assert.*
import org.junit.Test

class GamepadConfigTest {
    @Test fun defaultsCoverBothThreePositionSliders() {
        val defaults=defaultGamepadBindings(List(2) { SliderConfig() })
        assertEquals(GamepadBinding(0,0),defaults[GamepadButton.X])
        assertEquals(GamepadBinding(0,1),defaults[GamepadButton.Y])
        assertEquals(GamepadBinding(0,2),defaults[GamepadButton.L2])
        assertEquals(GamepadBinding(1,0),defaults[GamepadButton.L1])
        assertEquals(GamepadBinding(1,1),defaults[GamepadButton.R1])
        assertEquals(GamepadBinding(1,2),defaults[GamepadButton.R2])
    }
    @Test fun defaultsRespectExistingCustomPositions() {
        val defaults=defaultGamepadBindings(listOf(SliderConfig(values=listOf(10,20)),SliderConfig(values=listOf(90,40,0))))
        assertEquals(GamepadBinding(0,20),defaults[GamepadButton.Y])
        assertEquals(GamepadBinding(1,40),defaults[GamepadButton.R1])
        assertEquals(GamepadBinding(1,0),defaults[GamepadButton.R2])
    }
    @Test fun changedPositionsReconcileOnlyInvalidBindingValues() {
        val slots=listOf(SliderConfig(values=listOf(10,20,30)),SliderConfig(stepped=false))
        assertEquals(GamepadBinding(0,10),GamepadBinding(0,1).reconcile(slots))
        assertEquals(GamepadBinding(0,20),GamepadBinding(0,20).reconcile(slots))
        assertEquals(GamepadBinding(1,725),GamepadBinding(1,725).reconcile(slots))
    }
    @Test fun bindingLimitsMatchProtocol() {
        for(binding in listOf(-1 to 0,2 to 0,0 to -1,1 to 1001)) {
            try { GamepadBinding(binding.first,binding.second);fail("Invalid binding accepted") } catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun pressFiresOnceAndReleaseDoesNotSetAnotherValue() {
        val buttons=GamepadButtons()
        assertTrue(buttons.key(GamepadButton.X,true))
        assertFalse(buttons.key(GamepadButton.X,true))
        assertFalse(buttons.key(GamepadButton.X,false))
        assertTrue(buttons.key(GamepadButton.X,true))
    }
    @Test fun triggerUsesHysteresisAndDigitalEventsDoNotDuplicateAxisPress() {
        val buttons=GamepadButtons()
        assertFalse(buttons.trigger(GamepadButton.L2,.59f))
        assertTrue(buttons.trigger(GamepadButton.L2,.6f))
        assertFalse(buttons.key(GamepadButton.L2,true))
        assertFalse(buttons.trigger(GamepadButton.L2,.5f))
        assertFalse(buttons.key(GamepadButton.L2,false))
        assertFalse(buttons.trigger(GamepadButton.L2,.4f))
        assertTrue(buttons.trigger(GamepadButton.L2,.7f))
    }
    @Test fun analogTriggerIgnoresEarlyDigitalPressAndRelease() {
        val buttons=GamepadButtons()
        assertFalse(buttons.key(GamepadButton.L2,true,hasAnalogTrigger=true))
        assertFalse(buttons.trigger(GamepadButton.L2,.3f))
        assertTrue(buttons.trigger(GamepadButton.L2,.6f))
        assertFalse(buttons.key(GamepadButton.L2,false,hasAnalogTrigger=true))
        assertFalse(buttons.trigger(GamepadButton.L2,.5f))
        assertFalse(buttons.trigger(GamepadButton.L2,.4f))
        assertTrue(buttons.trigger(GamepadButton.L2,.7f))
    }
    @Test fun clearingHeldInputAllowsNextPress() {
        val buttons=GamepadButtons();assertTrue(buttons.key(GamepadButton.Y,true))
        buttons.clear();assertTrue(buttons.key(GamepadButton.Y,true))
    }
}
