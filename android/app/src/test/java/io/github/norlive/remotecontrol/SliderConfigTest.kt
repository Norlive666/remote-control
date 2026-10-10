package io.github.norlive.remotecontrol

import org.junit.Assert.*
import org.junit.Test

class SliderConfigTest {
    @Test fun defaultSliderOnlyOutputsThreeActualValues() {
        val c=SliderConfig()
        assertEquals(listOf(0,0,1,1,2,2),listOf(0,249,250,749,750,1000).map(c::map))
    }
    @Test fun customValuesAndInversePositionMatchSlots() {
        val c=SliderConfig(values=listOf(0,10,100))
        assertEquals(10,c.map(500));assertEquals(500f,c.position(10),0f)
        assertEquals(100,c.map(2000));assertEquals(0,c.map(-100))
        assertEquals(0f,c.position(7),0f)
    }
    @Test fun continuousModePreservesOldValues() {
        val c=SliderConfig(stepped=false)
        assertEquals(420,c.map(420));assertEquals(420f,c.position(420),0f)
    }
    @Test fun configuredSlotValueIsEncodedDirectly() {
        val c=SliderConfig(values=listOf(0,10,100))
        val s=ControlSession();s.enable(true);s.slider(0,c.map(500))
        val f=ControlProtocol.encode(s.state,0)
        assertEquals(10,f[12].toInt());assertEquals(0,f[13].toInt())
    }
    @Test fun firstValueCanBeNonZeroAndValidationRejectsInvalidLists() {
        assertEquals(10,SliderConfig(values=listOf(10,20)).map(0))
        for(values in listOf(listOf(1),listOf(0,1001),listOf(-1,2))) {
            try { SliderConfig(values=values);fail("invalid slots accepted") } catch(_:IllegalArgumentException) {}
        }
    }
}
