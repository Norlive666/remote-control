package io.github.norlive.remotecontrol

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

internal val Ink=Color(0xff1e293b)
internal val Muted=Color(0xff64748b)
internal val Accent=Color(0xff0288d1)
internal val Surface=Color(0xfff4f7fb)
internal val Line=Color(0xffe6edf5)
internal val Pad=Color(0xffeaf1f8)

class MainActivity : ComponentActivity() {
    private val model: RemoteViewModel by viewModels()
    private val permissionRequest=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result.values.all { it })model.refreshDevices() else model.error("蓝牙权限未授予，请在系统应用设置中允许附近设备权限")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window,window.decorView).apply {
            isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true
        }
        setContent {
            MaterialTheme(colorScheme=lightColorScheme(primary=Accent,background=Surface,surface=Color.White,onSurface=Ink,onBackground=Ink,primaryContainer=Pad,onPrimaryContainer=Ink,secondaryContainer=Line,onSecondaryContainer=Ink)) {
                ControllerScreen(model,::findDevices,{startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))})
            }
        }
    }
    private fun findDevices() {
        val missing=model.permissions().filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isEmpty())model.refreshDevices() else permissionRequest.launch(missing.toTypedArray())
    }
    override fun onStart() { super.onStart();model.foreground(true) }
    override fun onStop() { model.foreground(false);super.onStop() }
}

@Composable
private fun ControllerScreen(vm: RemoteViewModel,scan: ()->Unit,systemBluetooth: ()->Unit) {
    var connectionDialog by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf(false) }
    val state=vm.controls
    fun action(name: String) {
        when(name) {
            "connect" -> { connectionDialog=true;if(!vm.connected && !vm.connecting)scan() }
            "reset" -> vm.reset()
            "stop" -> vm.stop()
            "enable" -> vm.enable(!state.enabled)
        }
    }
    EditablePanel(vm) { name ->
        if(name=="settings")settingsDialog=true else if(!vm.layoutEditing)action(name)
    }
    if(connectionDialog) {
        AlertDialog(onDismissRequest={connectionDialog=false;vm.stopScan()},title={Text("蓝牙连接")},text={
            Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { LinkMode.entries.forEach { mode -> FilterChip(vm.mode==mode,{vm.changeMode(mode);scan()},label={Text(if(mode==LinkMode.HC05)"HC-05" else "BT630 BLE")}) } }
                Text(if(vm.mode==LinkMode.HC05)"先在系统蓝牙中配对 HC-05，再选择设备。" else "选择 BT630；连接时检查 FFF0 / FFF2 特征。",fontSize=12.sp)
                Text(vm.status,fontSize=12.sp,color=Muted)
                vm.devices.forEach { device ->
                    OutlinedButton(onClick={vm.connect(device);connectionDialog=false},modifier=Modifier.fillMaxWidth(),enabled=!vm.connecting) {
                        Column(Modifier.fillMaxWidth()) { Text(device.name);Text(device.address,fontSize=11.sp,color=Muted) }
                    }
                }
                Text("连接表示蓝牙链路就绪；当前协议不含 STM32 收包确认。",fontSize=11.sp,color=Muted)
            }
        },confirmButton={TextButton(scan){Text(if(vm.scanning)"重新扫描" else "刷新设备")}},dismissButton={
            Row { TextButton(systemBluetooth){Text("系统蓝牙")};if(vm.connected || vm.connecting)TextButton({vm.disconnect();connectionDialog=false}){Text("断开")} }
        })
    }
    if(settingsDialog) SettingsDialog(vm,onEdit={settingsDialog=false;vm.beginLayoutEdit()}) { settingsDialog=false }
}

@Composable
internal fun KeyButton(label: String,action: String,danger: Boolean=false,enabled: Boolean=true,onClick: ()->Unit) {
    val icon=when(action) {"connect"->Icons.Outlined.Bluetooth;"reset"->Icons.Outlined.Refresh;"stop"->Icons.Outlined.StopCircle;"enable"->Icons.Outlined.PowerSettingsNew;else->Icons.Outlined.Tune}
    FilledTonalButton(onClick,enabled=enabled,shape=RoundedCornerShape(13.dp),contentPadding=PaddingValues(horizontal=10.dp,vertical=6.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=if(danger)Color(0xfffff1f2)else Color.White,contentColor=if(danger)Color(0xffef4444)else Ink),modifier=Modifier.widthIn(min=48.dp)) {
        Column(horizontalAlignment=Alignment.CenterHorizontally) { Icon(icon,contentDescription=null,modifier=Modifier.size(18.dp));Text(label,style=MaterialTheme.typography.labelLarge.copy(fontSize=11.sp,lineHeight=14.sp),maxLines=1,softWrap=false) }
    }
}

/** Separate gesture scope per stick; a pointer never transfers from one stick to the other. */
@Composable
internal fun Joystick(label: String,x: Int,y: Int,active: Boolean,onMove: (Int,Int)->Unit) {
    val currentMove by rememberUpdatedState(onMove)
    BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        val side=minOf(maxWidth,maxHeight,260.dp)
        Canvas(Modifier.testTag(label).size(side).clip(RoundedCornerShape(22.dp)).background(Pad).border(1.dp,Line,RoundedCornerShape(22.dp))
            .semantics { contentDescription="$label，X $x，Y $y" }
            .pointerInput(active) {
                if(!active)return@pointerInput
                fun update(position: Offset) {
                    currentMove(((position.x-size.width/2f)/(size.width*.34f)*1000).roundToInt().coerceIn(-1000,1000),(-(position.y-size.height/2f)/(size.height*.34f)*1000).roundToInt().coerceIn(-1000,1000))
                }
                try {
                    awaitEachGesture {
                        val down=awaitFirstDown(requireUnconsumed=false);down.consume();update(down.position)
                        try { drag(down.id) { change -> if(change.positionChanged()){change.consume();update(change.position)} } }
                        finally { currentMove(0,0) }
                    }
                } finally { currentMove(0,0) }
            }) {
            val center=Offset(size.width/2,size.height/2)
            drawLine(Color(0xffcbd5e1),Offset(0f,center.y),Offset(size.width,center.y),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))
            drawLine(Color(0xffcbd5e1),Offset(center.x,0f),Offset(center.x,size.height),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))
            drawRoundRect(Line,topLeft=Offset(size.width*.13f,size.height*.13f),size=androidx.compose.ui.geometry.Size(size.width*.74f,size.height*.74f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),style=Stroke(1.dp.toPx()))
            val knob=Offset(center.x+x/1000f*size.width*.34f,center.y-y/1000f*size.height*.34f)
            val radius=minOf(28.dp.toPx(),size.width*.15f)
            drawCircle(Color.White,radius,knob);drawCircle(Brush.radialGradient(listOf(Color(0xff38bdf8),Accent,Color(0xff01579b)),center=knob-Offset(radius*.35f,radius*.35f),radius=radius*1.5f),radius-3.5.dp.toPx(),knob)
        }
    }
}

@Composable
private fun SettingsDialog(vm: RemoteViewModel,onEdit: ()->Unit,onClose: ()->Unit) {
    val labels=mapOf("connect" to "连接面板","reset" to "数值复位","stop" to "急停归零","enable" to "切换使能","hide" to "隐藏")
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth(.8f).heightIn(max=340.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text("面板设置",fontSize=18.sp);TextButton(onClose){Text("完成")} }
                OutlinedButton(onEdit,modifier=Modifier.fillMaxWidth()){Text("编辑布局")}
                vm.keys.forEachIndexed { i,key ->
                    var text by remember(i) { mutableStateOf(key.label) }
                    var expanded by remember { mutableStateOf(false) }
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(text,{text=it.take(6);vm.setKey(i,text,key.action)},label={Text("按钮 ${i+1} 名称")},singleLine=true,modifier=Modifier.weight(1f))
                        Box { OutlinedButton({expanded=true}){Text(labels[key.action] ?: key.action)};DropdownMenu(expanded,{expanded=false}){labels.forEach { (action,name)->DropdownMenuItem(text={Text(name)},onClick={vm.setKey(i,text,action);expanded=false}) }} }
                    }
                }
                Text("HC-05：20 Hz / STM32 默认 9600；BT630：50 Hz / STM32 115200。",fontSize=12.sp,color=Muted)
                Text("最近提交的数据帧（不代表 STM32 已收到）",fontSize=12.sp)
                Text(vm.lastFrame,fontSize=11.sp,fontFamily=FontFamily.Monospace,color=Muted)
                if(vm.mode==LinkMode.BT630) {
                    Text("最近收到的 FFF1 数据（原始数据，非控制确认）",fontSize=12.sp)
                    Text(vm.lastReceived,fontSize=11.sp,fontFamily=FontFamily.Monospace,color=Muted)
                }
                Text("第三方组件：Nordic Android BLE Library，BSD-3-Clause。许可证见仓库 THIRD_PARTY_NOTICES.md。",fontSize=11.sp,color=Muted)
            }
        }
    }
}
