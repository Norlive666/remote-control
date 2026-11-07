package io.github.norlive.remotecontrol

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun SliderSettings(vm: RemoteViewModel) {
    var channel by rememberSaveable { mutableIntStateOf(0) }
    val channels = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.sliderConfigs.indices.forEach { index ->
                FilterChip(channel == index, { channel = index }, label = { Text("滑块 ${index + 1}") },
                    modifier = Modifier.testTag("slider-channel-$index"))
            }
        }
        Box(Modifier.weight(1f)) {
            channels.SaveableStateProvider(channel) { SliderChannelSettings(vm, channel) }
        }
    }
}

@Composable
private fun SliderChannelSettings(vm: RemoteViewModel, index: Int) {
    val config = vm.sliderConfigs[index]
    var stepped by rememberSaveable(config) { mutableStateOf(config.stepped) }
    // A list of strings is saveable, including temporarily invalid/empty input.
    var values by rememberSaveable(config) { mutableStateOf(config.values.map(Int::toString)) }
    var applied by rememberSaveable { mutableStateOf(false) }
    val parsed = values.map(String::toIntOrNull)
    val valid = parsed.distinct().size == parsed.size && parsed.all { it != null && it in 0..1000 }
    val keyboard = LocalSoftwareKeyboardController.current
    SettingsPage("滑块 ${index + 1}", "设置当前通道的输出方式", footer = {
        Text(if (applied) "已应用" else "仅应用当前通道", fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
        TextButton({
            stepped = config.stepped; values = config.values.map(Int::toString); applied = false; keyboard?.hide()
        }, modifier = Modifier.testTag("slider-reset-$index")) { Text("撤销修改") }
        Button(onClick = {
            if (!stepped || valid) {
                vm.setSliderConfig(index, SliderConfig(stepped, if (stepped) parsed.filterNotNull() else config.values))
                applied = true; keyboard?.hide()
            }
        }, enabled = !stepped || valid, modifier = Modifier.testTag("slider-apply-$index")) { Text("应用参数") }
    }) {
        SettingsCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(stepped, { stepped = true; applied = false }, label = { Text("分档") },
                    modifier = Modifier.testTag("slider-stepped-$index"))
                FilterChip(!stepped, { stepped = false; applied = false }, label = { Text("连续") },
                    modifier = Modifier.testTag("slider-continuous-$index"))
            }
            if (stepped) {
                Text("${values.size} 档 · 各档数值为 0–1000，不可重复", fontSize = 12.sp, color = Muted)
                Slider(values.size.toFloat(), { raw ->
                    val count = raw.roundToInt()
                    val next = values.take(count).toMutableList()
                    // Choose unused values when adding a slot so a valid draft stays valid.
                    while (next.size < count) {
                        val used = next.mapNotNull(String::toIntOrNull).toSet()
                        next.add((0..1000).first { it !in used }.toString())
                    }
                    values = next.toList(); applied = false
                }, valueRange = 2f..8f, steps = 5, modifier = Modifier.testTag("slider-count-$index"))
                values.chunked(2).forEachIndexed { row, entries ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        entries.forEachIndexed { column, text ->
                            val slot = row * 2 + column
                            OutlinedTextField(text, { value ->
                                values = values.mapIndexed { i, old -> if (i == slot) value else old }; applied = false
                            }, label = { Text("第 ${slot + 1} 档") }, singleLine = true,
                                isError = parsed[slot] == null || parsed[slot] !in 0..1000 || parsed.count { it == parsed[slot] } > 1,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f).testTag("slider-value-$index-$slot"))
                        }
                        if (entries.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (!valid) Text("请填写 0–1000 内互不重复的整数。", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            } else {
                Text("主界面显示 0–100，线上连续发送 0–1000。", fontSize = 13.sp, color = Muted)
            }
        }
        SettingsHint("应用后回到第一档；连续模式回到 0。受影响的手柄映射会自动校正。")
    }
}
