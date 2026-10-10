package io.github.norlive.remotecontrol

import kotlin.math.roundToInt

data class SliderConfig(val stepped: Boolean = true,val values: List<Int> = listOf(0,1,2)) {
    init { require(values.size in 2..8 && values.all { it in 0..1000 }) }
    fun map(position: Int): Int = if(stepped)values[(position.coerceIn(0,1000)/1000f*(values.size-1)).roundToInt()] else position.coerceIn(0,1000)
    fun position(value: Int): Float = if(stepped)values.indexOf(value).coerceAtLeast(0)*1000f/(values.size-1) else value.coerceIn(0,1000).toFloat()
}
