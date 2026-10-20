package io.github.norlive.remotecontrol

enum class GamepadButton(val title: String) { X("X"), Y("Y"), L1("L1 · 左肩键"), R1("R1 · 右肩键"), L2("L2 · 左扳机"), R2("R2 · 右扳机") }
data class GamepadBinding(val slider: Int,val value: Int) {
    init { require(slider in 0..1 && value in 0..1000) }
    fun reconcile(configs: List<SliderConfig>): GamepadBinding {
        val config=configs[slider]
        return if(config.stepped && value !in config.values)copy(value=config.values.first()) else this
    }
}
fun defaultGamepadBindings(configs: List<SliderConfig>): Map<GamepadButton,GamepadBinding> {
    fun binding(slider: Int,position: Int): GamepadBinding {
        val config=configs[slider]
        val value=if(config.stepped)config.values[when(position) { 0 -> 0;1 -> config.values.size/2;else -> config.values.lastIndex }] else position
        return GamepadBinding(slider,value)
    }
    return mapOf(GamepadButton.X to binding(0,0),GamepadButton.Y to binding(0,1),GamepadButton.L2 to binding(0,2),
        GamepadButton.L1 to binding(1,0),GamepadButton.R1 to binding(1,1),GamepadButton.R2 to binding(1,2))
}
class GamepadButtons {
    private val digital=mutableSetOf<GamepadButton>()
    private val analog=mutableSetOf<GamepadButton>()
    fun key(button: GamepadButton,pressed: Boolean,hasAnalogTrigger: Boolean=false): Boolean {
        if(hasAnalogTrigger && (button==GamepadButton.L2 || button==GamepadButton.R2))return false
        return update(button,pressed,digital)
    }
    fun trigger(button: GamepadButton,value: Float): Boolean {
        val pressed=if(button in analog)value>.4f else value>=.6f
        return update(button,pressed,analog)
    }
    private fun update(button: GamepadButton,pressed: Boolean,source: MutableSet<GamepadButton>): Boolean {
        val before=button in digital || button in analog
        if(pressed)source.add(button) else source.remove(button)
        return !before && (button in digital || button in analog)
    }
    fun clear() { digital.clear();analog.clear() }
}
