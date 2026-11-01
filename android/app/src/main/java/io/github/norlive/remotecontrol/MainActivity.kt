package io.github.norlive.remotecontrol

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
    private val model by lazy { (application as RemoteApplication).controller }
    internal val gamepad by lazy { GamepadInput(this,model) }
    private sealed interface BluetoothAction {
        data object Scan: BluetoothAction
        data object LastDevice: BluetoothAction
        data class Select(val row: DeviceRow): BluetoothAction
    }
    private data class PermissionIntent(val action: BluetoothAction,val generation: Int)
    private var pendingPermission: PermissionIntent?=null
    private var launchedPermission: PermissionIntent?=null
    private var permissionResult: Boolean?=null
    private var started=false
    private val permissionRequest=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val request=launchedPermission
        launchedPermission=null
        if(request!=null && pendingPermission===request) {
            permissionResult=result.isNotEmpty() && result.values.all { it }
            completePermission()
        }
    }
    private fun completePermission() {
        if(!started)return
        val granted=permissionResult ?: return
        val request=pendingPermission
        pendingPermission=null;permissionResult=null
        if(request==null || request.generation!=model.connectionGeneration)return
        if(granted)performBluetooth(request.action)
        else model.error(if(request.action==BluetoothAction.Scan)"请在系统应用设置中允许附近设备和精确位置权限，定位权限仅用于蓝牙搜索" else "请在系统应用设置中允许附近设备连接权限")
    }
    private fun performBluetooth(action: BluetoothAction) {
        when(action) {
            BluetoothAction.Scan -> model.refreshDevices()
            BluetoothAction.LastDevice -> model.connectLastDevice()
            is BluetoothAction.Select -> model.connect(action.row)
        }
    }
    private fun requestBluetooth(action: BluetoothAction) {
        // A second activation cancels the pending action; an old dialog result cannot replay it.
        if(launchedPermission!=null) { pendingPermission=null;permissionResult=null;return }
        val required=if(action==BluetoothAction.Scan)model.permissions()
            else if(android.os.Build.VERSION.SDK_INT>=31)arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
        val granted=required.filter { checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED }.toSet()
        val missing=if(action==BluetoothAction.Scan)model.permissions(granted) else required.filterNot { it in granted }.toTypedArray()
        if(missing.isEmpty())performBluetooth(action)
        else {
            val request=PermissionIntent(action,model.connectionGeneration)
            pendingPermission=request;launchedPermission=request
            permissionRequest.launch(missing)
        }
    }
    private fun cancelConnection() { pendingPermission=null;permissionResult=null;model.disconnect() }
    private fun closeDevices() {
        if(pendingPermission?.action==BluetoothAction.Scan) { pendingPermission=null;permissionResult=null }
        model.stopScan()
    }
    private val notificationRequest=registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private fun backgroundConnection(value: Boolean) {
        model.updateKeepBackground(value)
        if(value && android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            notificationRequest.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window,window.decorView).apply {
            isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true
        }
        setContent {
            MaterialTheme(colorScheme=lightColorScheme(primary=Accent,background=Surface,surface=Color.White,onSurface=Ink,onBackground=Ink,primaryContainer=Pad,onPrimaryContainer=Ink,secondaryContainer=Line,onSecondaryContainer=Ink)) {
                ControllerScreen(model,::backgroundConnection,::findDevices,{requestBluetooth(BluetoothAction.LastDevice)},
                    {requestBluetooth(BluetoothAction.Select(it))},::cancelConnection,::closeDevices,{startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))})
            }
        }
    }
    private fun findDevices() { requestBluetooth(BluetoothAction.Scan) }
    override fun onStart() { super.onStart();started=true;model.foreground(true);gamepad.start();completePermission() }
    override fun onStop() {
        started=false;pendingPermission=null;permissionResult=null
        gamepad.stop();model.foreground(false);super.onStop()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus);gamepad.focus(hasFocus) }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = gamepad.key(event) || super.dispatchKeyEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = gamepad.motion(event) || super.dispatchGenericMotionEvent(event)
}

@Composable
private fun ControllerScreen(vm: RemoteViewModel,backgroundConnection: (Boolean)->Unit,scan: ()->Unit,
    connectLast: ()->Unit,connect: (DeviceRow)->Unit,cancel: ()->Unit,closeDevices: ()->Unit,systemBluetooth: ()->Unit) {
    var connectionDialog by remember { mutableStateOf(false) }
    fun openDevices() { connectionDialog=true;if(!vm.connected && !vm.connecting)scan() }
    EditablePanel(vm) { name ->
        if(name=="settings")vm.updateSettingsOpen(true)
        else if(!vm.layoutEditing)when(name) {
            "devices" -> openDevices()
            "connect" -> when {
                vm.connected || vm.connecting -> cancel()
                vm.lastDevice!=null -> connectLast()
                else -> openDevices()
            }
        }
    }
    if(connectionDialog) Bt630ConnectionDialog(vm,scan,systemBluetooth,connect,cancel) {
        closeDevices();connectionDialog=false
    }
    if(vm.settingsOpen) SettingsDialog(vm,backgroundConnection,onEdit={vm.updateSettingsOpen(false);vm.beginLayoutEdit()}) { vm.updateSettingsOpen(false) }
}

/** Separate gesture scope per stick; a pointer never transfers from one stick to the other. */
@Composable
internal fun Joystick(label: String,x: Int,y: Int,active: Boolean,config: JoystickConfig,onMove: (Int,Int)->Unit) {
    val currentMove by rememberUpdatedState(onMove)
    BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        val side=minOf(maxWidth,maxHeight)
        Canvas(Modifier.testTag(label).size(side).clip(RoundedCornerShape(22.dp)).background(Pad).border(1.dp,Line,RoundedCornerShape(22.dp))
            .semantics { contentDescription="$label，X $x，Y $y" }
            .pointerInput(active,config) {
                if(!active)return@pointerInput
                fun update(position: Offset) {
                    currentMove(((position.x-size.width/2f)/(size.width*.34f)*1000).roundToInt(),(-(position.y-size.height/2f)/(size.height*.34f)*1000).roundToInt())
                }
                awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false);down.consume();update(down.position)
                    try { drag(down.id) { change -> if(change.positionChanged()){change.consume();update(change.position)} } }
                    finally { currentMove(0,0) }
                }
            }) {
            val center=Offset(size.width/2,size.height/2)
            drawLine(Color(0xffcbd5e1),Offset(0f,center.y),Offset(size.width,center.y),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))
            drawLine(Color(0xffcbd5e1),Offset(center.x,0f),Offset(center.x,size.height),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))
            drawRoundRect(Line,topLeft=Offset(size.width*.13f,size.height*.13f),size=androidx.compose.ui.geometry.Size(size.width*.74f,size.height*.74f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),style=Stroke(1.dp.toPx()))
            val knob=Offset(center.x+x/config.maxValue.toFloat()*size.width*.34f,center.y-y/config.maxValue.toFloat()*size.height*.34f)
            val radius=minOf(28.dp.toPx(),size.width*.15f)
            drawCircle(Color.White,radius,knob);drawCircle(Brush.radialGradient(listOf(Color(0xff38bdf8),Accent,Color(0xff01579b)),center=knob-Offset(radius*.35f,radius*.35f),radius=radius*1.5f),radius-minOf(3.5.dp.toPx(),radius*.25f),knob)
        }
    }
}

@Composable
private fun SettingsDialog(vm: RemoteViewModel,backgroundConnection: (Boolean)->Unit,onEdit: ()->Unit,onClose: ()->Unit) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(shape=RoundedCornerShape(20.dp),modifier=Modifier.testTag("controller-settings").fillMaxWidth(.8f).heightIn(max=340.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text("面板设置",fontSize=18.sp);TextButton(onClose,modifier=Modifier.testTag("settings-done")){Text("完成")} }
                OutlinedButton(onEdit,modifier=Modifier.fillMaxWidth()){Text("编辑布局")}
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(vm.keepBackground,backgroundConnection,modifier=Modifier.testTag("background-connection"))
                    Column {
                        Text("后台保持连接")
                        Text("保留使能和当前控制值；通知中可手动断开。系统仍可能停止应用。",fontSize=11.sp,color=Muted)
                    }
                }
                PanelControlsSettings(vm)
                GamepadSettings(vm)
                SliderSettings(vm)
                vm.stickConfigs.forEachIndexed { index,config ->
                    var deadZone by remember(config) { mutableStateOf(config.deadZonePercent.toFloat()) }
                    var range by remember(config) { mutableStateOf(config.maxValue.toString()) }
                    val parsedRange=range.toIntOrNull()
                    Text(if(index==0)"左摇杆" else "右摇杆",fontWeight=FontWeight.Bold)
                    Text("死区 ${deadZone.roundToInt()}%",fontSize=12.sp)
                    Slider(deadZone,{deadZone=it},valueRange=0f..30f,steps=29,modifier=Modifier.testTag("stick-deadzone-$index"))
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(range,{range=it},label={Text("最大绝对值（1～1000）")},prefix={Text("±")},
                            singleLine=true,isError=parsedRange==null || parsedRange !in 1..1000,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f).testTag("stick-range-$index"))
                        OutlinedButton({vm.setStickConfig(index,config.copy(deadZonePercent=deadZone.roundToInt(),maxValue=parsedRange!!))},
                            enabled=parsedRange!=null && parsedRange in 1..1000,modifier=Modifier.testTag("stick-apply-$index")){Text("应用")}
                    }
                    HorizontalDivider()
                }
                Text("自由与单轴模式分别应用各轴死区；四向模式按距离应用死区，输出横向或纵向（45° 优先纵向），力度连续变化。修改设置后该摇杆归零，松手后重新操作。",fontSize=11.sp,color=Muted)
                Text("BT630：${1000/BT630_PERIOD_MS} Hz / STM32 115200。",fontSize=12.sp,color=Muted)
                Text("最近提交的数据帧（不代表 STM32 已收到）",fontSize=12.sp)
                Text(vm.lastFrame,fontSize=11.sp,fontFamily=FontFamily.Monospace,color=Muted)
                Text("最近收到的 FFF1 数据（原始数据，非控制确认）",fontSize=12.sp)
                Text(vm.lastReceived,fontSize=11.sp,fontFamily=FontFamily.Monospace,color=Muted)
            }
        }
    }
}
