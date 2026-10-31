package io.github.norlive.remotecontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.UUID

@Composable
internal fun PanelControlsSettings(vm: RemoteViewModel) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PanelControl?>(null) }
    OutlinedButton({open=true},modifier=Modifier.fillMaxWidth().testTag("panel-controls-settings")){Text("自定义控件")}
    if(open)Dialog(onDismissRequest={open=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth(.85f).heightIn(max=340.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("自定义控件")
                    TextButton({open=false},modifier=Modifier.testTag("panel-controls-done")){Text("完成")}
                }
                Text("可添加任意数量按钮；滑块最多 4 个。标题与小字说明可分别设置。")
                vm.panelControls.forEach { control ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text(control.title);Text("${control.type.title} · 滑块 ${control.channel+1}",style=MaterialTheme.typography.bodySmall) }
                        TextButton({editing=control},modifier=Modifier.testTag("control-edit-${control.id}")){Text("编辑")}
                        TextButton({vm.deletePanelControl(control.id)},modifier=Modifier.testTag("control-delete-${control.id}")){Text("删除")}
                    }
                }
                OutlinedButton({editing=PanelControl("control-${UUID.randomUUID()}","按钮")},modifier=Modifier.testTag("control-add")){Text("添加控件")}
            }
        }
    }
    editing?.let { original -> PanelControlEditor(original,vm.panelControls.count { it.type==PanelControlType.SLIDER && it.id!=original.id }<4,
        onSave={vm.savePanelControl(it);editing=null},onClose={editing=null}) }
}

@Composable
private fun PanelControlEditor(original: PanelControl,allowSlider: Boolean,onSave: (PanelControl)->Unit,onClose: ()->Unit) {
    var title by remember(original) { mutableStateOf(original.title) }
    var note by remember(original) { mutableStateOf(original.note) }
    var type by remember(original) { mutableStateOf(original.type) }
    var target by remember(original) { mutableStateOf(original.channel) }
    var press by remember(original) { mutableStateOf(original.pressValue.toString()) }
    var long by remember(original) { mutableStateOf(original.longValue.toString()) }
    var cycle by remember(original) { mutableStateOf(original.cycleValues.joinToString(",")) }
    val pressValue=press.toIntOrNull();val longValue=long.toIntOrNull();val cycleValues=cycle.replace('，',',').split(',').map { it.trim().toIntOrNull() }
    val valid=title.isNotBlank() && when(type) {
        PanelControlType.SLIDER -> allowSlider
        PanelControlType.MOMENTARY -> pressValue!=null && pressValue in 0..1000
        PanelControlType.SHORT_LONG -> pressValue!=null && pressValue in 0..1000 && longValue!=null && longValue in 0..1000
        PanelControlType.CYCLE -> cycleValues.size in 2..8 && cycleValues.distinct().size==cycleValues.size && cycleValues.all { it!=null && it in 0..1000 }
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth(.85f).heightIn(max=340.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("编辑控件")
                OutlinedTextField(title,{title=it},singleLine=true,label={Text("标题")},modifier=Modifier.fillMaxWidth().testTag("control-title"))
                OutlinedTextField(note,{note=it.replace('\n',' ')},singleLine=true,label={Text("小字说明（可留空）")},modifier=Modifier.fillMaxWidth().testTag("control-note"))
                ControlChoice(type.title,"control-type",PanelControlType.entries.filter { it!=PanelControlType.SLIDER || allowSlider }.map { it.name to it.title }) { type=PanelControlType.valueOf(it) }
                ControlChoice("目标：滑块 ${target+1}","control-channel",(0..3).map { it.toString() to "滑块 ${it+1}" }) { target=it.toInt() }
                if(type==PanelControlType.MOMENTARY || type==PanelControlType.SHORT_LONG) {
                    OutlinedTextField(press,{press=it},singleLine=true,label={Text(if(type==PanelControlType.MOMENTARY)"按下值（0～1000）" else "短按值（0～1000）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.testTag("control-press-value"))
                }
                if(type==PanelControlType.SHORT_LONG)OutlinedTextField(long,{long=it},singleLine=true,label={Text("长按值（0～1000）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.testTag("control-long-value"))
                if(type==PanelControlType.CYCLE)OutlinedTextField(cycle,{cycle=it},singleLine=true,label={Text("循环值（2～8 个不同值，逗号分隔）")},modifier=Modifier.testTag("control-cycle-values"))
                Text(when(type) { PanelControlType.SLIDER -> "使用目标通道的滑块档位设置。";PanelControlType.MOMENTARY -> "按下赋值，松开归零。";PanelControlType.SHORT_LONG -> "短按松开后发送 100 ms 脉冲；按住 500 ms 触发长按，松开归零。";PanelControlType.CYCLE -> "每次点击切换到下一数值，松开保持。" },style=MaterialTheme.typography.bodySmall)
                Row { TextButton(onClose){Text("取消")};Button({onSave(original.copy(title=title.trim(),note=note,channel=target,type=type,pressValue=pressValue?.coerceIn(0,1000) ?: 1,longValue=longValue?.coerceIn(0,1000) ?: 2,cycleValues=if(type==PanelControlType.CYCLE)cycleValues.map { it!! } else original.cycleValues))},enabled=valid,modifier=Modifier.testTag("control-save")){Text("保存控件")} }
            }
        }
    }
}

@Composable
private fun ControlChoice(label: String,tag: String,choices: List<Pair<String,String>>,choose: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({expanded=true},modifier=Modifier.testTag(tag)){Text(label)}
        DropdownMenu(expanded,{expanded=false}) { choices.forEach { (key,title) -> DropdownMenuItem(text={Text(title)},onClick={expanded=false;choose(key)},modifier=Modifier.testTag("$tag-$key")) } }
    }
}
