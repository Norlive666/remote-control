package io.github.norlive.remotecontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlin.math.roundToInt
import java.io.IOException

enum class LinkMode(val title: String, val period: Long) { BLE_UART("BLE 串口 · FFE0/FFE1",50), BT630("BT630 · BLE",20) }
data class DeviceRow(val name: String, val address: String, val device: BluetoothDevice,val viaBle: Boolean=false,val viaSystem: Boolean=false) {
    val source: String get()=if(viaBle && viaSystem)"BLE + 系统发现" else if(viaBle)"BLE" else "系统发现"
}
internal fun mergeDeviceRows(rows: List<DeviceRow>,incoming: DeviceRow): List<DeviceRow> {
    val old=rows.firstOrNull { it.address==incoming.address }
    val merged=incoming.copy(
        name=if(incoming.name=="未命名 BLE 设备" && old!=null)old.name else incoming.name,
        viaBle=incoming.viaBle || old?.viaBle==true,viaSystem=incoming.viaSystem || old?.viaSystem==true)
    return (rows.filterNot { it.address==incoming.address }+merged).sortedBy { it.name }
}

@SuppressLint("MissingPermission")
class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences=application.getSharedPreferences("controller",0)
    private val adapter=(application.getSystemService(BluetoothManager::class.java))?.adapter
    private val main=Handler(Looper.getMainLooper())
    private val session=ControlSession()
    private val layoutSession=LayoutEditSession(layoutIds.mapNotNull { id ->
        if(!preferences.contains("layout.$id.x") || !preferences.contains("layout.$id.y"))null
        else {
            val x=preferences.getFloat("layout.$id.x",0f);val y=preferences.getFloat("layout.$id.y",0f)
            if(x.isFinite() && y.isFinite())id to PanelPosition(x.coerceIn(0f,1f),y.coerceIn(0f,1f)) else null
        }
    }.toMap(),layoutIds.mapNotNull { id ->
        val scale=preferences.getFloat("layout.$id.scale",1f)
        if(preferences.contains("layout.$id.scale") && scale.isFinite())id to scale.coerceIn(.3f,2.5f) else null
    }.toMap())
    var layoutEditing by mutableStateOf(false); private set
    var layoutPositions by mutableStateOf(layoutSession.positions); private set
    var layoutScales by mutableStateOf(layoutSession.scales); private set
    var selectedLayoutId by mutableStateOf("leftPad"); private set
    var padCanvas by mutableStateOf(preferences.getBoolean("layout.padCanvas",false) || layoutSession.saved.keys.none { it=="leftPad" || it=="rightPad" }); private set
    var controls by mutableStateOf(session.state); private set
    var mode by mutableStateOf(LinkMode.entries.firstOrNull { it.name==preferences.getString("mode",null) } ?: LinkMode.BLE_UART); private set
    var status by mutableStateOf("未连接"); private set
    var connected by mutableStateOf(false); private set
    var connecting by mutableStateOf(false); private set
    var scanning by mutableStateOf(false); private set
    var devices by mutableStateOf(listOf<DeviceRow>()); private set
    var scanDetails by mutableStateOf(""); private set
    var lastReceived by mutableStateOf("—"); private set
    var lastFrame by mutableStateOf("—"); private set
    var sent by mutableLongStateOf(0); private set
    var stickConfigs by mutableStateOf(List(2) { index ->
        JoystickConfig(
            preferences.getInt("stick.$index.deadZone",5).coerceIn(0,30),
            preferences.getInt("stick.$index.range",1000).coerceIn(1,1000),
            preferences.getString("stick.$index.mode",null).let { saved ->
                if(saved=="EIGHT_WAY")JoystickMode.FOUR_WAY
                else JoystickMode.entries.firstOrNull { it.name==saved } ?: JoystickMode.FREE
            })
    }); private set
    var sliderConfigs by mutableStateOf(List(2) { index ->
        val parsed=(preferences.getString("slider.$index.values","0,1,2") ?: "0,1,2").split(',').map { it.toIntOrNull() }
        val values=if(parsed.size in 2..8 && parsed.distinct().size==parsed.size && parsed.all { it!=null && it in 0..1000 })parsed.map { it!! } else listOf(0,1,2)
        SliderConfig(preferences.getBoolean("slider.$index.stepped",true),values)
    }); private set
    init { sliderConfigs.forEachIndexed { i,config -> session.slider(i,config.map(0)) };sync() }
    private var transport: BluetoothTransport?=null
    private var connectJob: Job?=null
    private var sendJob: Job?=null
    private var scanCallback: ScanCallback?=null
    private var scanner: android.bluetooth.le.BluetoothLeScanner?=null
    private var scanTimeout: Runnable?=null
    private var scanReceiver: BroadcastReceiver?=null
    private var systemScanActive=false
    private var systemScanStarted=false
    private var bleResults=0
    private var systemResults=0
    private var bleScanNote="未启动"
    private var systemScanNote="未启动"
    private var generation=0
    private var foreground=false
    private var sequence=0

    fun permissions(): Array<String> = if(Build.VERSION.SDK_INT>=31)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)
        else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)
    fun error(message: String) { status=message }
    fun changeMode(value: LinkMode) {
        if(mode==value)return
        disconnect();mode=value;devices=emptyList();scanDetails="";preferences.edit().putString("mode",value.name).apply()
    }
    private fun updateScanDetails() {
        scanDetails="BLE：$bleResults 条（$bleScanNote）；系统：$systemResults 条（$systemScanNote）；可选 ${devices.size} 个"
    }
    private fun recordDevice(device: BluetoothDevice,name: String?,ble: Boolean) {
        if(ble)bleResults++ else systemResults++
        if(ble || device.type==BluetoothDevice.DEVICE_TYPE_LE || device.type==BluetoothDevice.DEVICE_TYPE_DUAL) {
            val item=DeviceRow(name?.takeIf { it.isNotBlank() } ?: device.name?.takeIf { it.isNotBlank() } ?: "未命名 BLE 设备",
                device.address,device,viaBle=ble,viaSystem=!ble)
            devices=mergeDeviceRows(devices,item)
        }
        updateScanDetails()
    }
    fun refreshDevices() {
        if(connected || connecting)disconnect() else stopScan()
        devices=emptyList();scanDetails="";bleResults=0;systemResults=0;bleScanNote="未启动";systemScanNote="未启动"
        if(adapter==null) { status="手机不支持蓝牙";return }
        if(!adapter.isEnabled) { status="请先开启手机蓝牙";return }
        val application=getApplication<Application>()
        val location=application.getSystemService(LocationManager::class.java)
        val locationEnabled=if(Build.VERSION.SDK_INT>=28)location?.isLocationEnabled==true
            else Settings.Secure.getInt(application.contentResolver,Settings.Secure.LOCATION_MODE,Settings.Secure.LOCATION_MODE_OFF)!=Settings.Secure.LOCATION_MODE_OFF
        if(!locationEnabled) { status="请开启手机定位开关，用于蓝牙设备搜索";return }
        scanning=true;status="正在扫描 BLE 和系统设备…"
        systemScanStarted=adapter.isDiscovering
        val receiver=object: BroadcastReceiver() {
            override fun onReceive(context: Context,intent: Intent) {
                if(scanReceiver!==this || !foreground)return
                when(intent.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> { systemScanStarted=true;systemScanActive=true;systemScanNote="扫描中";updateScanDetails() }
                    BluetoothDevice.ACTION_FOUND -> {
                        val device=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)
                            else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device?.let { recordDevice(it,null,false) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if(systemScanStarted && !adapter.isDiscovering) {
                        systemScanActive=false;systemScanNote="结束";updateScanDetails()
                        if(scanCallback==null)finishScan()
                    }
                }
            }
        }
        try {
            val filter=IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND);addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
            if(Build.VERSION.SDK_INT>=33)application.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED)
            else application.registerReceiver(receiver,filter)
            scanReceiver=receiver
            systemScanActive=adapter.isDiscovering || adapter.startDiscovery()
            systemScanNote=if(systemScanActive)"扫描中" else "启动失败"
        } catch(e: RuntimeException) { systemScanNote="启动失败：${e.message ?: "权限或系统状态异常"}" }
        val currentScanner=adapter.bluetoothLeScanner
        if(currentScanner==null)bleScanNote="不可用"
        else {
            val callback=object: ScanCallback() {
                override fun onScanResult(callbackType: Int,result: ScanResult) {
                    main.post { if(scanCallback===this && foreground)recordDevice(result.device,result.scanRecord?.deviceName,true) }
                }
                override fun onScanFailed(errorCode: Int) {
                    main.post {
                        if(scanCallback===this) {
                            runCatching { scanner?.stopScan(this) };scanCallback=null;bleScanNote="失败 $errorCode";updateScanDetails()
                            if(!systemScanActive)finishScan()
                        }
                    }
                }
            }
            scanner=currentScanner;scanCallback=callback
            try { currentScanner.startScan(callback);bleScanNote="扫描中" }
            catch(e: RuntimeException) { scanCallback=null;bleScanNote="启动失败：${e.message ?: "权限或系统状态异常"}" }
        }
        updateScanDetails()
        if(scanCallback==null && !systemScanActive)finishScan()
        else scanTimeout=Runnable { finishScan() }.also { main.postDelayed(it,20000) }
    }
    private fun finishScan() {
        if(bleScanNote=="扫描中")bleScanNote="结束"
        if(systemScanNote=="扫描中")systemScanNote="结束"
        stopScan()
        status=if(devices.isEmpty())"未发现 BLE 设备，请检查模块是否正在广播" else "请选择 BLE 设备"
    }
    fun stopScan() {
        scanTimeout?.let { main.removeCallbacks(it) };scanTimeout=null
        val callback=scanCallback;scanCallback=null;scanning=false
        if(callback!=null)runCatching { scanner?.stopScan(callback) }
        scanner=null
        val receiver=scanReceiver;scanReceiver=null
        if(systemScanActive)runCatching { adapter?.cancelDiscovery() }
        systemScanActive=false;systemScanStarted=false
        if(receiver!=null)runCatching { getApplication<Application>().unregisterReceiver(receiver) }
        if(bleScanNote=="扫描中")bleScanNote="已停止"
        if(systemScanNote=="扫描中")systemScanNote="已停止"
        if(scanDetails.isNotEmpty())updateScanDetails()
        if(status.startsWith("正在扫描"))status="扫描已停止"
    }
    fun connect(row: DeviceRow) {
        disconnect();if(!foreground)return
        if(adapter?.isEnabled!=true){status="请先开启手机蓝牙";return}
        val token=generation
        val link=BleUartTransport(getApplication(),
            lost={ main.post { if(token==generation && connected) fail("蓝牙连接已断开") } },
            received={ bytes -> main.post { if(token==generation)lastReceived=bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) } } },generic=mode==LinkMode.BLE_UART)
        transport=link;lastReceived="—";connecting=true;status="连接 ${row.name}…"
        adapter.cancelDiscovery()
        connectJob=viewModelScope.launch {
            try {
                val watchdog=launch { delay(12000);if(token==generation && connecting)fail("连接超时，请检查模块供电与 BLE 模式") }
                try { link.open(row.device) } finally { watchdog.cancel() }
                if(token!=generation || !foreground){link.close();return@launch}
                connected=true;connecting=false;status="已连接 ${row.name} · 请手动使能";sent=0
                startSending(link,token)
            } catch(e: CancellationException){throw e} catch(e: Exception){if(token==generation)fail(e.message ?: "连接失败")}
        }
    }
    private fun startSending(link: BluetoothTransport, token: Int) {
        sendJob=viewModelScope.launch {
            try {
                while(isActive && token==generation && foreground) {
                    val frame=ControlProtocol.encode(session.state,sequence++)
                    // Await each write before sampling again: no queue of old stick positions.
                    link.send(frame)
                    if(token!=generation)return@launch
                    sent++;lastFrame=frame.joinToString(" ") { "%02X".format(it.toInt() and 255) }
                    delay(mode.period)
                }
            } catch(e: CancellationException){throw e} catch(e: Exception){if(token==generation)fail(e.message ?: "发送失败")}
        }
    }
    private fun fail(message: String) { disconnect();status=message }
    fun disconnect() {
        generation++;stopScan();transport?.close();transport=null
        connectJob?.cancel();connectJob=null;sendJob?.cancel();sendJob=null
        connected=false;connecting=false;session.suspendControl();sync();status="未连接"
    }
    fun foreground(value: Boolean) { foreground=value;if(!value)disconnect() }
    fun axes(left: Boolean,x: Int,y: Int) {
        if(layoutEditing)return
        val (mappedX,mappedY)=stickConfigs[if(left)0 else 1].map(x,y)
        session.axes(left,mappedX,mappedY);sync()
    }
    fun setStickConfig(index: Int,config: JoystickConfig) {
        if(layoutEditing)return
        session.axes(index==0,0,0);sync()
        stickConfigs=stickConfigs.mapIndexed { i,current -> if(i==index)config else current }
        preferences.edit().putInt("stick.$index.deadZone",config.deadZonePercent)
            .putInt("stick.$index.range",config.maxValue).putString("stick.$index.mode",config.mode.name).apply()
    }
    fun cycleStickMode(index: Int) {
        val current=stickConfigs[index]
        setStickConfig(index,current.copy(mode=JoystickMode.entries[(current.mode.ordinal+1)%JoystickMode.entries.size]))
    }
    fun slider(index: Int,value: Int) { if(layoutEditing)return;session.slider(index,sliderConfigs[index].map(value));sync() }
    fun setSliderConfig(index: Int,config: SliderConfig) {
        if(layoutEditing)return
        sliderConfigs=sliderConfigs.mapIndexed { i,current -> if(i==index)config else current }
        preferences.edit().putBoolean("slider.$index.stepped",config.stepped)
            .putString("slider.$index.values",config.values.joinToString(",")).apply()
        session.slider(index,config.map(0));sync()
    }
    fun enable(value: Boolean) {
        if(value && (!connected || !foreground || layoutEditing))return
        session.enable(value)
        if(value)sliderConfigs.forEachIndexed { i,config ->
            val current=if(i==0)session.state.slider1 else session.state.slider2
            session.slider(i,config.map(config.position(current).roundToInt()))
        }
        sync()
    }
    fun beginLayoutEdit() {
        if(layoutEditing || !padCanvas)return
        session.enable(false);sync();layoutSession.begin();syncLayout()
    }
    fun selectPanel(id: String) { if(layoutEditing && id in layoutIds)selectedLayoutId=id }
    fun resizePanel(scale: Float) { layoutSession.resize(selectedLayoutId,scale);syncLayout() }
    fun movePanel(id: String,position: PanelPosition) { selectPanel(id);layoutSession.move(id,position);syncLayout() }
    fun saveLayoutEdit() {
        layoutSession.save()
        val editor=preferences.edit().putBoolean("layout.padCanvas",padCanvas)
        layoutIds.forEach { id -> editor.remove("layout.$id.x").remove("layout.$id.y").remove("layout.$id.scale") }
        layoutSession.saved.forEach { (id,p) -> editor.putFloat("layout.$id.x",p.x).putFloat("layout.$id.y",p.y) }
        layoutSession.savedScales.forEach { (id,scale) -> editor.putFloat("layout.$id.scale",scale) }
        editor.apply();syncLayout()
    }
    fun migratePadPositions(converted: Map<String,PanelPosition>,scales: Map<String,Float>) {
        if(padCanvas || layoutEditing)return
        layoutSession.begin()
        converted.forEach { (id,position) -> if(id in layoutSession.saved)layoutSession.move(id,position) }
        scales.forEach { (id,scale) -> if(id in layoutSession.saved || id in layoutSession.savedScales)layoutSession.resize(id,scale) }
        layoutSession.save();padCanvas=true;saveLayoutEdit()
    }
    fun cancelLayoutEdit() { layoutSession.cancel();syncLayout() }
    fun defaultLayoutDraft() { layoutSession.restoreDefaults();syncLayout() }
    private fun syncLayout() { layoutEditing=layoutSession.editing;layoutPositions=layoutSession.positions;layoutScales=layoutSession.scales }
    private fun sync() { controls=session.state }
    override fun onCleared() { disconnect();super.onCleared() }
    companion object {
        val layoutIds=listOf("leftStick","rightStick","leftSlider","rightSlider","enable","leftPad","rightPad")
    }
}
