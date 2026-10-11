package io.github.norlive.remotecontrol

import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.roundToInt

enum class JoystickMode(val title: String) { FREE("自由"), HORIZONTAL("仅横向"), VERTICAL("仅纵向"), FOUR_WAY("四向") }

data class JoystickConfig(val deadZonePercent: Int = 5,val maxValue: Int = 1000,val mode: JoystickMode = JoystickMode.FREE) {
    init { require(deadZonePercent in 0..30);require(maxValue in 1..1000) }
    fun map(x: Int,y: Int): Pair<Int,Int> {
        if(mode==JoystickMode.FOUR_WAY) {
            val rawX=x.toDouble();val rawY=y.toDouble()
            val distance=hypot(rawX,rawY).coerceAtMost(1000.0)
            val deadZone=deadZonePercent*10
            if(distance<=deadZone)return 0 to 0
            val magnitude=((distance-deadZone)*maxValue/(1000-deadZone)).roundToInt()
            return if(abs(rawX)>abs(rawY)) (if(x<0)-magnitude else magnitude) to 0
                else 0 to (if(y<0)-magnitude else magnitude)
        }
        fun axis(raw: Int): Int {
            val value=raw.coerceIn(-1000,1000)
            val deadZone=deadZonePercent*10
            if(abs(value)<=deadZone)return 0
            val magnitude=((abs(value)-deadZone)*maxValue/(1000f-deadZone)).roundToInt()
            return if(value<0)-magnitude else magnitude
        }
        return (if(mode==JoystickMode.VERTICAL)0 else axis(x)) to (if(mode==JoystickMode.HORIZONTAL)0 else axis(y))
    }
}
