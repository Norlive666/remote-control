package io.github.norlive.remotecontrol

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.abs
import kotlin.math.roundToInt

enum class JoystickMode(val title: String) { FREE("自由"), HORIZONTAL("仅横向"), VERTICAL("仅纵向"), EIGHT_WAY("八向") }

data class JoystickConfig(val deadZonePercent: Int = 5,val maxValue: Int = 1000,val mode: JoystickMode = JoystickMode.FREE) {
    init { require(deadZonePercent in 0..30);require(maxValue in 1..1000) }
    fun map(x: Int,y: Int): Pair<Int,Int> {
        if(mode==JoystickMode.EIGHT_WAY) {
            val rawX=x.coerceIn(-1000,1000).toDouble();val rawY=y.coerceIn(-1000,1000).toDouble()
            val distance=hypot(rawX,rawY).coerceAtMost(1000.0)
            val deadZone=deadZonePercent*10
            if(distance<=deadZone)return 0 to 0
            val magnitude=(distance-deadZone)*maxValue/(1000-deadZone)
            val angle=round(atan2(rawY,rawX)/(PI/4))*(PI/4)
            return (magnitude*cos(angle)).roundToInt() to (magnitude*sin(angle)).roundToInt()
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
