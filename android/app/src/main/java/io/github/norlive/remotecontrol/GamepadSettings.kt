package io.github.norlive.remotecontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun GamepadSettings(vm: RemoteViewModel) {
    var resetGeneration by rememberSaveable { mutableIntStateOf(0) }
    SettingsPage("手柄按键映射", "按下设值，松开保持；每个按键单独保存", footer = {
        Text("左右摇杆沿用摇杆参数", fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
        OutlinedButton({ vm.resetGamepadBindings(); resetGeneration++ }, modifier = Modifier.testTag("gamepad-defaults")) {
            Text("恢复默认映射")
        }
    }) {
        SettingsHint("X5 Lite 使用安卓模式（深蓝灯），通过 Type-C 连接。关闭设置后拨动摇杆或按键以识别设备。")
        Text("当前手柄：${vm.gamepadName ?: "等待输入"}", fontSize = 13.sp, color = Muted)
        GamepadButton.entries.forEach { button ->
            key(button, resetGeneration, vm.gamepadBindings.getValue(button), vm.sliderConfigs) {
                GamepadBindingCard(vm, button)
            }
        }
        Text("扳机达到 60% 触发，降至 40% 后可再次触发。分档通道只能选择已有档值。",
            fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun GamepadBindingCard(vm: RemoteViewModel, button: GamepadButton) {
    val saved = vm.gamepadBindings.getValue(button)
    var target by rememberSaveable(saved, vm.sliderConfigs) { mutableIntStateOf(saved.slider) }
    var text by rememberSaveable(saved, vm.sliderConfigs) { mutableStateOf(saved.value.toString()) }
    val config = vm.sliderConfigs[target]
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 0..1000 && (!config.stepped || parsed in config.values)
    val keyboard = LocalSoftwareKeyboardController.current
    SettingsCard(Modifier.fillMaxWidth()) {
        Text(button.title, fontWeight = FontWeight.Bold)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Wrap controls on small displays / larger system font settings.
            val controls: @Composable RowScope.() -> Unit = {
                GamepadChoice("滑块 ${target + 1}", "gamepad-target-${button.name}", List(4) { "滑块 ${it + 1}" }) { index ->
                    target = index
                    if (vm.sliderConfigs[index].stepped && text.toIntOrNull() !in vm.sliderConfigs[index].values)
                        text = vm.sliderConfigs[index].values.first().toString()
                }
                if (config.stepped) {
                    GamepadChoice("数值 $text", "gamepad-value-${button.name}", config.values.map { "数值 $it" }) { index ->
                        text = config.values[index].toString()
                    }
                } else {
                    OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("数值 0–1000", fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid,
                        modifier = Modifier.weight(1f).testTag("gamepad-value-${button.name}"))
                }
            }
            if (maxWidth >= 420.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    controls()
                    OutlinedButton({ vm.setGamepadBinding(button, GamepadBinding(target, parsed!!)); keyboard?.hide() },
                        enabled = valid, modifier = Modifier.testTag("gamepad-apply-${button.name}")) { Text("保存") }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = controls)
                    OutlinedButton({ vm.setGamepadBinding(button, GamepadBinding(target, parsed!!)); keyboard?.hide() },
                        enabled = valid, modifier = Modifier.testTag("gamepad-apply-${button.name}")) { Text("保存映射") }
                }
            }
        }
    }
}

@Composable
private fun GamepadChoice(label: String, tag: String, choices: List<String>, choose: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, modifier = Modifier.testTag(tag)) { Text(label) }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEachIndexed { index, title ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; choose(index) },
                    modifier = Modifier.testTag("$tag-option-$index"))
            }
        }
    }
}
