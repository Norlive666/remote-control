package io.github.norlive.remotecontrol

data class ControlState(val lx: Int = 0, val ly: Int = 0, val rx: Int = 0, val ry: Int = 0, val slider1: Int = 500, val slider2: Int = 500, val enabled: Boolean = false, val stopped: Boolean = false)
object ControlProtocol {
    fun encode(state: ControlState, sequence: Int): ByteArray = error("Not implemented")
    fun crc(data: ByteArray, from: Int = 0, until: Int = data.size): Int = error("Not implemented")
}
class ControlSession {
    var state = ControlState(); private set
    fun axes(left: Boolean, x: Int, y: Int) { }
    fun slider(index: Int, value: Int) { }
    fun enable(value: Boolean) { }
    fun stop() { }
    fun suspendControl() { }
    fun reset() { }
}
