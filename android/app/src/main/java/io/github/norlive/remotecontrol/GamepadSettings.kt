package io.github.norlive.remotecontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun GamepadSettings(vm: RemoteViewModel) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton({open=true},modifier=Modifier.fillMaxWidth().testTag("gamepad-settings")){Text("手柄按键映射")}
    if(open)Dialog(onDismissRequest={open=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth(.85f).heightIn(max=340.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("手柄按键映射",fontWeight=FontWeight.Bold)
                    TextButton({open=false},modifier=Modifier.testTag("gamepad-done")){Text("完成")}
                }
                Text("手柄：${vm.gamepadName ?: "等待摇杆或按键输入"}")
                Text("X5 Lite 请使用安卓模式（深蓝灯），通过 Type-C 连接。左右摇杆沿用面板设置。")
                Text("按下设值，松开保持。扳机达到 60% 触发，回到 40% 后可再次触发。")
                Text("拔出、切后台或打开弹窗会关闭使能并清零，返回后需手动使能。")
                Text("最近按键：${vm.lastGamepadInput}")
                Text("最近输入事件：${vm.gamepadInputInfo}")
                Text("关闭弹窗后拨动摇杆或按键以自动识别设备；设备名由安卓系统提供，不按名称过滤。")
                GamepadButton.entries.forEach { button ->
                    val saved=vm.gamepadBindings.getValue(button)
                    var target by remember(saved,vm.sliderConfigs) { mutableStateOf(saved.slider) }
                    var text by remember(saved,vm.sliderConfigs) { mutableStateOf(saved.value.toString()) }
                    val config=vm.sliderConfigs[target]
                    val parsed=text.toIntOrNull()
                    val valid=parsed!=null && parsed in 0..1000 && (!config.stepped || parsed in config.values)
                    Text(button.title,fontWeight=FontWeight.Bold)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        GamepadChoice("滑块 ${target+1}","gamepad-target-${button.name}",listOf("滑块 1","滑块 2")) { index ->
                            target=index
                            if(vm.sliderConfigs[index].stepped && text.toIntOrNull() !in vm.sliderConfigs[index].values)text=vm.sliderConfigs[index].values.first().toString()
                        }
                        if(config.stepped)GamepadChoice("数值 $text","gamepad-value-${button.name}",config.values.map { "数值 $it" }) { index -> text=config.values[index].toString() }
                    }
                    if(!config.stepped)OutlinedTextField(text,{text=it},singleLine=true,label={Text("数值（0～1000）")},
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),isError=!valid,
                        modifier=Modifier.fillMaxWidth().testTag("gamepad-value-${button.name}"))
                    OutlinedButton({vm.setGamepadBinding(button,GamepadBinding(target,parsed!!))},enabled=valid,
                        modifier=Modifier.testTag("gamepad-apply-${button.name}")){Text("保存 ${button.name} 映射")}
                    HorizontalDivider()
                }
                OutlinedButton(vm::resetGamepadBindings,modifier=Modifier.testTag("gamepad-defaults")){Text("恢复默认按键映射")}
                Text("分档滑块只能选择已有档位；需其他数值请先修改滑块设置。连续滑块可设置 0～1000。修改档位后，失效映射回到该滑块第一档。")
            }
        }
    }
}

@Composable
private fun GamepadChoice(label: String,tag: String,choices: List<String>,onSelect: (Int)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({expanded=true},modifier=Modifier.testTag(tag)){Text(label)}
        DropdownMenu(expanded,{expanded=false}) {
            choices.forEachIndexed { index,title -> DropdownMenuItem(text={Text(title)},onClick={expanded=false;onSelect(index)},modifier=Modifier.testTag("$tag-option-$index")) }
        }
    }
}
