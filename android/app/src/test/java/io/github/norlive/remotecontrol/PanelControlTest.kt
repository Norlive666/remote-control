package io.github.norlive.remotecontrol

import org.junit.Assert.*
import org.junit.Test

class PanelControlTest {
    private class Clock {
        var now=0L
        val tasks=mutableListOf<Pair<Long,()->Unit>>()
        fun schedule(delay: Long,block: ()->Unit): ()->Unit {
            val task=now+delay to block;tasks.add(task);return { tasks.remove(task);Unit }
        }
        fun advance(ms: Long) { val end=now+ms;while(true) { val task=tasks.filter { it.first<=end }.minByOrNull { it.first } ?: break;tasks.remove(task);now=task.first;task.second() };now=end }
    }
    @Test(expected=IllegalArgumentException::class) fun duplicateCycleValuesAreRejected() {
        PanelControl("c","cycle",type=PanelControlType.CYCLE,cycleValues=listOf(0,0))
    }
    @Test fun defaultsKeepOnlyOriginalSliders() {
        assertEquals(listOf("leftSlider","rightSlider"),defaultPanelControls().map { it.id })
        assertTrue(defaultPanelControls().all { it.type==PanelControlType.SLIDER && it.note.isEmpty() })
    }
    @Test fun shortReleasePulsesFor100msAndLongNeverPulsesShort() {
        val clock=Clock();val values=IntArray(4)
        val buttons=PanelButtonController(clock::schedule,{clock.now},{values[it]}) { channel,value -> values[channel]=value }
        val control=PanelControl("a","a",type=PanelControlType.SHORT_LONG,channel=3,pressValue=5,longValue=9)
        buttons.press(control);clock.advance(499);buttons.release(control.id)
        assertEquals(5,values[3]);clock.advance(99);assertEquals(5,values[3])
        clock.advance(1);assertEquals(0,values[3])
        buttons.press(control);clock.advance(500);assertEquals(9,values[3])
        buttons.release(control.id);assertEquals(0,values[3]);clock.advance(1000);assertEquals(0,values[3])
    }
    @Test fun cycleUsesCurrentChannelIncludingOtherInputWrites() {
        val clock=Clock();val values=IntArray(4)
        val buttons=PanelButtonController(clock::schedule,{clock.now},{values[it]}) { channel,value -> values[channel]=value }
        val c=PanelControl("c","cycle",type=PanelControlType.CYCLE)
        buttons.press(c);buttons.release(c.id);assertEquals(1,values[0])
        buttons.assign(0,2);buttons.press(c);buttons.release(c.id);assertEquals(0,values[0])
    }
    @Test fun exact500msReleaseDoesNotEmitShortWhenTimerHasNotRun() {
        val clock=Clock();val emitted=mutableListOf<Int>()
        val buttons=PanelButtonController(clock::schedule,{clock.now}) { _,value -> emitted.add(value) }
        val control=PanelControl("a","a",type=PanelControlType.SHORT_LONG,pressValue=5,longValue=9)
        buttons.press(control);clock.now=500;buttons.release(control.id);clock.advance(1000)
        assertEquals(listOf(0),emitted)
    }
    @Test fun newerWriteOwnsChannelAndCancelClearsAllChannels() {
        val clock=Clock();val values=IntArray(4)
        val buttons=PanelButtonController(clock::schedule,{clock.now},{values[it]}) { channel,value -> values[channel]=value }
        val short=PanelControl("a","a",type=PanelControlType.SHORT_LONG)
        buttons.press(short);buttons.release(short.id);buttons.assign(0,800)
        clock.advance(1000);assertEquals(800,values[0])
        val held=PanelControl("b","b",type=PanelControlType.MOMENTARY)
        buttons.press(held);buttons.assign(0,900);buttons.release(held.id);assertEquals(900,values[0])
        buttons.press(short);buttons.cancelAll();clock.advance(1000);assertArrayEquals(IntArray(4),values)
    }
    @Test fun cycleAdvancesOnReleaseAndCancelDoesNotClick() {
        val clock=Clock();val values=IntArray(4)
        val buttons=PanelButtonController(clock::schedule,{clock.now},{values[it]}) { channel,value -> values[channel]=value }
        val c=PanelControl("c","cycle",type=PanelControlType.CYCLE,cycleValues=listOf(4,7))
        buttons.press(c);buttons.release(c.id);assertEquals(4,values[0])
        buttons.press(c);buttons.release(c.id);assertEquals(7,values[0])
        buttons.press(c);buttons.cancel(c.id);assertEquals(7,values[0])
    }
}
