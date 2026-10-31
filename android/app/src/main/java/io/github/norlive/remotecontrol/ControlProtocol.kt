package io.github.norlive.remotecontrol

data class ControlState(val lx: Int = 0, val ly: Int = 0, val rx: Int = 0, val ry: Int = 0, val slider1: Int = 500, val slider2: Int = 500, val enabled: Boolean = false, val stopped: Boolean = false, val slider3: Int = 0, val slider4: Int = 0) {
    fun sliderValue(index: Int): Int = when(index) {
        0 -> slider1; 1 -> slider2; 2 -> slider3; 3 -> slider4
        else -> throw IllegalArgumentException("Slider index must be 0..3")
    }
}
object ControlProtocol {
    fun encode(state: ControlState, sequence: Int): ByteArray {
        val frame=ByteArray(20)
        frame[0]=0xa5.toByte();frame[1]=0x5a;frame[2]=2;frame[3]=sequence.toByte()
        val active=state.enabled && !state.stopped
        val values=if(active) intArrayOf(state.lx.coerceIn(-1000,1000),state.ly.coerceIn(-1000,1000),state.rx.coerceIn(-1000,1000),state.ry.coerceIn(-1000,1000)) else IntArray(4)
        values.forEachIndexed { i,v -> frame[4+i*2]=v.toByte();frame[5+i*2]=(v shr 8).toByte() }
        var sliders=0L
        if(active)for(i in 0..3)sliders=sliders or (state.sliderValue(i).coerceIn(0,1000).toLong() shl (10*i))
        for(i in 0..4)frame[12+i]=(sliders shr (8*i)).toByte()
        frame[17]=((if(active) 1 else 0) or (if(state.stopped) 2 else 0)).toByte()
        val sum=crc(frame,2,18);frame[18]=sum.toByte();frame[19]=(sum shr 8).toByte()
        return frame
    }
    fun crc(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
        var sum=0xffff
        for(i in from until until) {
            sum=sum xor ((data[i].toInt() and 255) shl 8)
            repeat(8) { sum=if(sum and 0x8000 != 0) (sum shl 1) xor 0x1021 else sum shl 1;sum=sum and 0xffff }
        }
        return sum
    }
}
class ControlSession {
    var state = ControlState(); private set
    fun axes(left: Boolean, x: Int, y: Int) {
        if(state.stopped)return
        state=if(left)state.copy(lx=x.coerceIn(-1000,1000),ly=y.coerceIn(-1000,1000)) else state.copy(rx=x.coerceIn(-1000,1000),ry=y.coerceIn(-1000,1000))
    }
    fun slider(index: Int, value: Int) {
        require(index in 0..3)
        if(state.stopped)return
        val v=value.coerceIn(0,1000)
        state=when(index) { 0 -> state.copy(slider1=v); 1 -> state.copy(slider2=v); 2 -> state.copy(slider3=v); else -> state.copy(slider4=v) }
    }
    fun enable(value: Boolean) { state=state.copy(enabled=value,stopped=if(value)false else state.stopped,lx=0,ly=0,rx=0,ry=0) }
    fun stop() { state=ControlState(slider1=0,slider2=0,stopped=true) }
    fun suspendControl() { state=state.copy(lx=0,ly=0,rx=0,ry=0,slider1=0,slider2=0,slider3=0,slider4=0,enabled=false) }
    fun reset() { state=state.copy(lx=0,ly=0,rx=0,ry=0,slider1=if(state.stopped)0 else 500,slider2=if(state.stopped)0 else 500,slider3=0,slider4=0) }
}
