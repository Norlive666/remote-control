package io.github.norlive.remotecontrol

import org.junit.Assert.*
import org.junit.Test

class JoystickConfigTest {
    @Test fun independentDeadZonesRemoveCrossAxisDrift() {
        val config=JoystickConfig()
        assertEquals(500 to 0,config.map(525,40))
        assertEquals(0 to -500,config.map(-40,-525))
        assertEquals(500 to 500,config.map(525,525))
    }
    @Test fun deadZoneBoundaryAndReleaseAreZero() {
        val config=JoystickConfig(deadZonePercent=10)
        assertEquals(0 to 0,config.map(100,-100))
        assertEquals(1 to -1,config.map(101,-101))
        assertEquals(0 to 0,config.map(0,0))
    }
    @Test fun singleAxisModesIgnoreTheOtherAxis() {
        assertEquals(1000 to 0,JoystickConfig(mode=JoystickMode.HORIZONTAL).map(1000,-1000))
        assertEquals(0 to -1000,JoystickConfig(mode=JoystickMode.VERTICAL).map(1000,-1000))
    }
    @Test fun rangeEndpointsAreSymmetricAndClamped() {
        assertEquals(255 to -255,JoystickConfig(maxValue=255).map(2000,-2000))
        assertEquals(1 to -1,JoystickConfig(maxValue=1).map(1000,-1000))
        assertEquals(128 to -128,JoystickConfig(deadZonePercent=0,maxValue=255).map(500,-500))
    }
    @Test fun zeroDeadZonePreservesExistingCoordinates() {
        assertEquals(882 to -882,JoystickConfig(deadZonePercent=0).map(882,-882))
    }
    @Test fun configuredOutputIsTransmittedWithoutRescaling() {
        val (x,y)=JoystickConfig(maxValue=255).map(1000,40)
        val session=ControlSession();session.enable(true);session.axes(true,x,y)
        val frame=ControlProtocol.encode(session.state,1)
        assertEquals(255,(frame[4].toInt() and 255) or ((frame[5].toInt() and 255) shl 8))
        assertEquals(0,frame[6].toInt());assertEquals(0,frame[7].toInt())
    }
}
