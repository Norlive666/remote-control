package io.github.norlive.remotecontrol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private enum class SettingsSection(val title: String, val tag: String, val icon: ImageVector) {
    CONNECTION("连接与后台", "settings-connection", Icons.Outlined.Link),
    STICKS("摇杆参数", "settings-sticks", Icons.Outlined.SportsEsports),
    SLIDERS("滑块档位", "settings-sliders", Icons.Outlined.Tune),
    CONTROLS("自定义控件", "panel-controls-settings", Icons.Outlined.Widgets),
    GAMEPAD("手柄映射", "gamepad-settings", Icons.Outlined.Gamepad),
    DIAGNOSTICS("通信诊断", "settings-diagnostics", Icons.Outlined.MonitorHeart)
}

@Composable
internal fun SettingsDialog(
    vm: RemoteViewModel,
    backgroundConnection: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onClose: () -> Unit
) {
    var selectedName by rememberSaveable { mutableStateOf(SettingsSection.STICKS.name) }
    val selected = SettingsSection.valueOf(selectedName)
    val pages = rememberSaveableStateHolder()
    val keyboard = LocalSoftwareKeyboardController.current
    SettingsWindow(onClose) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp)) {
            val navigationWidth = if (maxWidth >= 760.dp) 172.dp else 140.dp
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White,
                modifier = Modifier.fillMaxSize().testTag("controller-settings")) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, tint = Ink)
                        Spacer(Modifier.width(8.dp))
                        Text("面板设置", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onEdit, modifier = Modifier.testTag("settings-edit-layout")) {
                            Text("编辑布局")
                        }
                        IconButton(onClick = onClose, modifier = Modifier.testTag("settings-done")) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭设置")
                        }
                    }
                    HorizontalDivider(color = Line)
                    Row(Modifier.weight(1f)) {
                        Column(Modifier.width(navigationWidth).fillMaxHeight()
                            .verticalScroll(rememberScrollState()).selectableGroup().padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SettingsSection.entries.forEach { section ->
                                val active = section == selected
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (active) Color(0xffe3f3ff) else Color.Transparent)
                                    .selectable(selected = active, role = Role.Tab, onClick = {
                                        keyboard?.hide()
                                        selectedName = section.name
                                    }).testTag(section.tag).padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(section.icon, contentDescription = null,
                                        modifier = Modifier.size(20.dp), tint = if (active) Accent else Muted)
                                    Spacer(Modifier.width(8.dp))
                                    Text(section.title, fontSize = 14.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) Accent else Ink)
                                }
                            }
                        }
                        VerticalDivider(color = Line)
                        Box(Modifier.weight(1f).fillMaxHeight().background(Surface)) {
                            // Keep unsaved fields and scroll positions when moving between categories.
                            pages.SaveableStateProvider(selected.name) {
                                when (selected) {
                                    SettingsSection.CONNECTION -> ConnectionSettingsPage(vm, backgroundConnection, onClose)
                                    SettingsSection.STICKS -> StickSettingsPage(vm)
                                    SettingsSection.SLIDERS -> SliderSettings(vm)
                                    SettingsSection.CONTROLS -> PanelControlsSettings(vm)
                                    SettingsSection.GAMEPAD -> GamepadSettings(vm)
                                    SettingsSection.DIAGNOSTICS -> DiagnosticsSettingsPage(vm, onClose)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Only the parameter area scrolls; the action bar stays visible above the keyboard. */
@Composable
internal fun SettingsPage(
    title: String,
    subtitle: String,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = Muted)
            }
            content()
        }
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically, content = footer)
    }
}

@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = Color.White,
        border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun SettingsHint(text: String) {
    Surface(color = Color(0xffe8f4fc), shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
            Text(text, fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
private fun StickSettingsPage(vm: RemoteViewModel) {
    val left = vm.stickConfigs[0]
    val right = vm.stickConfigs[1]
    var leftDead by rememberSaveable(left) { mutableStateOf(left.deadZonePercent.toFloat()) }
    var rightDead by rememberSaveable(right) { mutableStateOf(right.deadZonePercent.toFloat()) }
    var leftRange by rememberSaveable(left) { mutableStateOf(left.maxValue.toString()) }
    var rightRange by rememberSaveable(right) { mutableStateOf(right.maxValue.toString()) }
    var applied by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val valid = leftRange.toIntOrNull() in 1..1000 && rightRange.toIntOrNull() in 1..1000
    SettingsPage("摇杆参数", "分别调整左右摇杆的死区与输出范围", footer = {
        Text(if (applied) "已应用" else "未应用的修改不会保存", fontSize = 11.sp, color = Muted,
            modifier = Modifier.weight(1f))
        TextButton(onClick = {
            leftDead = left.deadZonePercent.toFloat(); rightDead = right.deadZonePercent.toFloat()
            leftRange = left.maxValue.toString(); rightRange = right.maxValue.toString(); applied = false
            keyboard?.hide()
        }, modifier = Modifier.testTag("sticks-reset-draft")) { Text("撤销修改") }
        Button(onClick = {
            // Validate both fields before making either change.
            val l = leftRange.toIntOrNull(); val r = rightRange.toIntOrNull()
            if (l != null && r != null && l in 1..1000 && r in 1..1000) {
                vm.setStickConfig(0, left.copy(deadZonePercent = leftDead.roundToInt(), maxValue = l))
                vm.setStickConfig(1, right.copy(deadZonePercent = rightDead.roundToInt(), maxValue = r))
                keyboard?.hide(); applied = true
            }
        }, enabled = valid, modifier = Modifier.testTag("sticks-apply")) { Text("应用参数") }
    }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val leftCard: @Composable (Modifier) -> Unit = { modifier ->
                StickParameterCard(0, leftDead, leftRange, { leftDead = it; applied = false },
                    { leftRange = it; applied = false }, modifier)
            }
            val rightCard: @Composable (Modifier) -> Unit = { modifier ->
                StickParameterCard(1, rightDead, rightRange, { rightDead = it; applied = false },
                    { rightRange = it; applied = false }, modifier)
            }
            if (maxWidth >= 440.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                leftCard(Modifier.weight(1f)); rightCard(Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                leftCard(Modifier.fillMaxWidth()); rightCard(Modifier.fillMaxWidth())
            }
        }
        SettingsHint("修改后摇杆归零，松手后重新操作。方向模式仍在主界面切换。")
        Text("自由与单轴模式分别应用各轴死区；四向模式按距离应用死区，45° 时优先纵向。",
            fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun StickParameterCard(index: Int, dead: Float, range: String,
    onDead: (Float) -> Unit, onRange: (String) -> Unit, modifier: Modifier) {
    SettingsCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (index == 0) "Ⓛ  左摇杆" else "Ⓡ  右摇杆", fontWeight = FontWeight.Bold,
                color = Ink, modifier = Modifier.weight(1f))
            Text("死区 ${dead.roundToInt()}%", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("0%", fontSize = 11.sp, color = Muted)
            Slider(dead, onDead, valueRange = 0f..30f, steps = 29,
                modifier = Modifier.weight(1f).testTag("stick-deadzone-$index"))
            Text("30%", fontSize = 11.sp, color = Muted)
        }
        OutlinedTextField(range, onRange, label = { Text("最大绝对值（1–1000）", fontSize = 12.sp) },
            prefix = { Text("±") }, singleLine = true, isError = range.toIntOrNull() !in 1..1000,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("stick-range-$index"))
    }
}

@Composable
private fun ConnectionSettingsPage(vm: RemoteViewModel, backgroundConnection: (Boolean) -> Unit, onClose: () -> Unit) {
    SettingsPage("连接与后台", "蓝牙设备仍从主界面的连接卡片选择", footer = {
        Text("后台开关即时生效", fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
        OutlinedButton(onClose) { Text("完成") }
    }) {
        SettingsCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("后台保持连接", fontWeight = FontWeight.Bold)
                    Text("切到其他应用时，保留使能和当前控制值。", fontSize = 12.sp, color = Muted)
                    Text("通知中可手动断开；系统仍可能停止应用。", fontSize = 11.sp, color = Muted)
                }
                Switch(vm.keepBackground, backgroundConnection, modifier = Modifier.testTag("background-connection"))
            }
        }
        DebugLinkSettings(vm)
    }
}

@Composable
private fun DiagnosticsSettingsPage(vm: RemoteViewModel, onClose: () -> Unit) {
    SettingsPage("通信诊断", "查看最近的数据与输入事件", footer = {
        Text("数据随当前会话更新", fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
        OutlinedButton(onClose) { Text("完成") }
    }) {
        SettingsHint("蓝牙提交成功不代表 STM32 已收到；FFF1 原始通知也不等于控制确认。")
        Text("BT630：名义 ${1000 / BT630_PERIOD_MS} Hz · STM32 UART 115200", fontSize = 12.sp, color = Muted)
        SettingsCard(Modifier.fillMaxWidth()) {
            Text("最近提交的数据帧", fontWeight = FontWeight.Bold)
            Text(vm.lastFrame, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted,
                modifier = Modifier.testTag("diagnostics-frame"))
        }
        SettingsCard(Modifier.fillMaxWidth()) {
            Text("最近收到的 FFF1 数据", fontWeight = FontWeight.Bold)
            Text(vm.lastReceived, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
        SettingsCard(Modifier.fillMaxWidth()) {
            Text("手柄输入", fontWeight = FontWeight.Bold)
            Text("设备：${vm.gamepadName ?: "等待输入"}", fontSize = 12.sp)
            Text("最近按键：${vm.lastGamepadInput}", fontSize = 12.sp, color = Muted)
            Text(vm.gamepadInputInfo, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
        }
    }
}
