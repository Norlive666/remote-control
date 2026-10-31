package io.github.norlive.remotecontrol

enum class PanelControlType(val title: String) { SLIDER("滑块"), MOMENTARY("普通按钮"), SHORT_LONG("短按 / 长按"), CYCLE("点击循环") }
data class PanelControl(
    val id: String,val title: String,val note: String="",val channel: Int=0,
    val type: PanelControlType=PanelControlType.MOMENTARY,val pressValue: Int=1,
    val longValue: Int=2,val cycleValues: List<Int> = listOf(0,1,2),
) {
    init { require(id.isNotBlank() && title.isNotBlank() && channel in 0..3 && pressValue in 0..1000 && longValue in 0..1000 && cycleValues.size in 2..8 && cycleValues.distinct().size==cycleValues.size && cycleValues.all { it in 0..1000 }) }
}
fun defaultPanelControls()=listOf(PanelControl("leftSlider","滑块 1",type=PanelControlType.SLIDER),PanelControl("rightSlider","滑块 2",channel=1,type=PanelControlType.SLIDER))

/** All output producers share channel ownership, so stale releases cannot erase a newer write. */
class PanelButtonController(private val schedule: (Long,()->Unit)->(()->Unit),private val now: ()->Long={System.nanoTime()/1_000_000},private val current: (Int)->Int={0},private val output: (Int,Int)->Unit) {
    private class Press(val control: PanelControl,val token: Long,val started: Long,var longFired: Boolean=false,var cancelTimer: ()->Unit={})
    private var serial=0L
    private val owners=LongArray(4)
    private val presses=mutableMapOf<String,Press>()
    private val pulses=mutableMapOf<String,Press>()
    fun assign(channel: Int,value: Int) { owners[channel]=++serial;output(channel,value) }
    private fun write(press: Press,value: Int) { if(owners[press.control.channel]==press.token)output(press.control.channel,value) }
    fun press(control: PanelControl) {
        cancel(control.id)
        val press=Press(control,++serial,now());owners[control.channel]=press.token;presses[control.id]=press
        when(control.type) {
            PanelControlType.MOMENTARY -> write(press,control.pressValue)
            PanelControlType.SHORT_LONG -> press.cancelTimer=schedule(500) { if(presses[control.id]===press) { press.longFired=true;write(press,control.longValue) } }
            else -> Unit
        }
    }
    fun release(id: String) {
        val press=presses.remove(id) ?: return
        press.cancelTimer()
        when(press.control.type) {
            PanelControlType.MOMENTARY -> write(press,0)
            PanelControlType.SHORT_LONG -> if(press.longFired || now()-press.started>=500)write(press,0) else {
                write(press,press.control.pressValue);pulses[id]=press
                press.cancelTimer=schedule(100) { if(pulses[id]===press) { pulses.remove(id);write(press,0) } }
            }
            PanelControlType.CYCLE -> {
                val index=(press.control.cycleValues.indexOf(current(press.control.channel))+1)%press.control.cycleValues.size
                write(press,press.control.cycleValues[index])
            }
            else -> Unit
        }
    }
    fun cancel(id: String) {
        val press=presses.remove(id) ?: pulses.remove(id) ?: return
        press.cancelTimer()
        if(press.control.type!=PanelControlType.CYCLE)write(press,0)
    }
    fun cancelAll() {
        presses.keys.toList().forEach(::cancel);pulses.keys.toList().forEach(::cancel)
        repeat(4) { assign(it,0) }
    }
}
