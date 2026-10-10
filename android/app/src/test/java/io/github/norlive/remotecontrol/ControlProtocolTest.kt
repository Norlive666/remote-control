package io.github.norlive.remotecontrol

import org.junit.Assert.*
import org.junit.Test

class ControlProtocolTest {
    @Test fun crcMatchesStandardCheckVector() { assertEquals(0x29b1, ControlProtocol.crc("123456789".toByteArray())) }
    @Test fun enabledAxesUseSignedLittleEndianAndValidCrc() {
        val frame = ControlProtocol.encode(ControlState(-1000, 1000, -1, 0, 0, 1000, true), 255)
        assertArrayEquals(byteArrayOf(0xa5.toByte(),0x5a,1,0xff.toByte(),0x18,0xfc.toByte(),0xe8.toByte(),3,0xff.toByte(),0xff.toByte(),0,0,0,0,0xe8.toByte(),3,1,0),frame.copyOf(18))
        assertEquals(20,frame.size)
        assertArrayEquals(byteArrayOf(0xdc.toByte(),0x37),frame.sliceArray(18..19))
        assertEquals(ControlProtocol.crc(frame,2,18), (frame[18].toInt() and 255) or ((frame[19].toInt() and 255) shl 8))
    }
    @Test fun disabledAndStoppedFramesNeverCarryMotion() {
        for (state in listOf(ControlState(1000, -1000, 900, 800, 1000, 1000), ControlState(1000,0,0,0,1000,1000,true,true))) {
            val f=ControlProtocol.encode(state,0)
            assertTrue(f.sliceArray(4..15).all { it==0.toByte() }); assertEquals(0,f[16].toInt() and 1)
        }
    }
    @Test fun outOfRangeValuesAreClamped() {
        val f=ControlProtocol.encode(ControlState(5000,-5000,0,0,-1,5000,true),0)
        assertArrayEquals(byteArrayOf(0xe8.toByte(),3,0x18,0xfc.toByte()),f.sliceArray(4..7))
        assertArrayEquals(byteArrayOf(0,0,0xe8.toByte(),3),f.sliceArray(12..15))
    }
    @Test fun stopClearsControlsAndRequiresExplicitEnable() {
        val s=ControlSession(); s.enable(true);s.axes(true,900,-800);s.slider(0,900);s.stop()
        assertFalse(s.state.enabled);assertTrue(s.state.stopped);assertEquals(0,s.state.lx);assertEquals(0,s.state.slider1)
        s.axes(true,500,500);s.reset();assertTrue(s.state.stopped)
        s.enable(true);assertTrue(s.state.enabled);assertFalse(s.state.stopped)
    }
    @Test fun backgroundAndConnectionChangeClearAllAxesAndDisable() {
        val s=ControlSession();s.enable(true);s.axes(false,1000,-1000);s.suspendControl()
        assertFalse(s.state.enabled);assertEquals(0,s.state.rx);assertEquals(0,s.state.ry)
    }
    @Test fun leftAndRightSticksAreIndependent() {
        val s=ControlSession();s.axes(true,200,300);s.axes(false,-400,500)
        assertEquals(200,s.state.lx);assertEquals(-400,s.state.rx);s.axes(true,0,0);assertEquals(500,s.state.ry)
    }
}
