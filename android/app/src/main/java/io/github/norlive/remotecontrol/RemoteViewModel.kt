package io.github.norlive.remotecontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlin.math.roundToInt
import java.io.IOException

enum class LinkMode(val title: String, val period: Long) { HC05("HC-05 · 经典蓝牙",50), BT630("BT630 · BLE",20) }
data class DeviceRow(val name: String, val address: String, val device: BluetoothDevice)

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
        if(preferences.contains("layout.$id.scale") && scale.isFinite())id to scale.coerceIn(.5f,1.5f) else null
    }.toMap())
    var layoutEditing by mutableStateOf(false); private set
    var layoutPositions by mutableStateOf(layoutSession.positions); private set
    var layoutScales by mutableStateOf(layoutSession.scales); private set
    var selectedLayoutId by mutableStateOf("leftPad"); private set
    var controls by mutableStateOf(session.state); private set
    var mode by mutableStateOf(if(preferences.getString("mode","HC05")=="BT630")LinkMode.BT630 else LinkMode.HC05); private set
    var status by mutableStateOf("未连接"); private set
    var connected by mutableStateOf(false); private set
    var connecting by mutableStateOf(false); private set
    var scanning by mutableStateOf(false); private set
    var devices by mutableStateOf(listOf<DeviceRow>()); private set
    var lastReceived by mutableStateOf("—"); private set
    var lastFrame by mutableStateOf("—"); private set
    var sent by mutableLongStateOf(0); private set
    var stickConfigs by mutableStateOf(List(2) { index ->
        JoystickConfig(
            preferences.getInt("stick.$index.deadZone",5).coerceIn(0,30),
            preferences.getInt("stick.$index.range",1000).coerceIn(1,1000),
            JoystickMode.entries.firstOrNull { it.name==preferences.getString("stick.$index.mode",null) } ?: JoystickMode.FREE)
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
    private var generation=0
    private var foreground=false
    private var sequence=0

    fun permissions(): Array<String> = if(android.os.Build.VERSION.SDK_INT>=31)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN)
        else if(mode==LinkMode.BT630) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyArray()
    fun error(message: String) { status=message }
    fun changeMode(value: LinkMode) {
        if(mode==value)return
        disconnect();mode=value;devices=emptyList();preferences.edit().putString("mode",value.name).apply()
    }
    fun refreshDevices() {
        stopScan();devices=emptyList()
        if(adapter==null) { status="手机不支持蓝牙";return }
        if(!adapter.isEnabled) { status="请先开启手机蓝牙";return }
        if(mode==LinkMode.HC05) {
            devices=adapter.bondedDevices.map { DeviceRow(it.name ?: "已配对设备",it.address,it) }.sortedBy { it.name }
            status=if(devices.isEmpty())"请在系统蓝牙设置中先配对 HC-05" else "请选择已配对的 HC-05"
            return
        }
        val currentScanner=adapter.bluetoothLeScanner ?: run { status="BLE 扫描不可用";return }
        val callback=object: ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post {
                    if(scanCallback!==this || !foreground)return@post
                    val item=DeviceRow(result.scanRecord?.deviceName ?: result.device.name ?: "未命名 BLE 设备",result.device.address,result.device)
                    devices=(devices.filterNot { it.address==item.address }+item).sortedBy { it.name }
                }
            }
            override fun onScanFailed(errorCode: Int) { main.post { if(scanCallback===this){stopScan();status="扫描失败：$errorCode"} } }
        }
        scanner=currentScanner;scanCallback=callback;scanning=true;status="正在扫描 BLE 设备…"
        currentScanner.startScan(null,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback)
        scanTimeout=Runnable { if(scanCallback===callback){stopScan();status=if(devices.isEmpty())"未发现 BLE 设备；检查模块供电与定位开关" else "请选择 BT630"} }.also { main.postDelayed(it,10000) }
    }
    fun stopScan() {
        scanTimeout?.let { main.removeCallbacks(it) };scanTimeout=null
        val callback=scanCallback;scanCallback=null;scanning=false
        if(callback!=null)runCatching { scanner?.stopScan(callback) }
        scanner=null
    }
    fun connect(row: DeviceRow) {
        disconnect();if(!foreground)return
        if(adapter?.isEnabled!=true){status="请先开启手机蓝牙";return}
        val token=generation
        val link=if(mode==LinkMode.HC05)ClassicTransport() else Bt630Transport(getApplication(),
            lost={ main.post { if(token==generation && connected) fail("蓝牙连接已断开") } },
            received={ bytes -> main.post { if(token==generation)lastReceived=bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) } } })
        transport=link;lastReceived="—";connecting=true;status="连接 ${row.name}…"
        adapter.cancelDiscovery()
        connectJob=viewModelScope.launch {
            try {
                // RFCOMM connect can block: close the socket from this watchdog.
                val watchdog=launch { delay(12000);if(token==generation && connecting)fail("连接超时，请检查配对与模块模式") }
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
        if(layoutEditing)return
        session.enable(false);sync();layoutSession.begin();syncLayout()
    }
    fun selectPanel(id: String) { if(layoutEditing && id in layoutIds)selectedLayoutId=id }
    fun resizePanel(scale: Float) { layoutSession.resize(selectedLayoutId,scale);syncLayout() }
    fun movePanel(id: String,position: PanelPosition) { selectPanel(id);layoutSession.move(id,position);syncLayout() }
    fun saveLayoutEdit() {
        layoutSession.save()
        val editor=preferences.edit()
        layoutIds.forEach { id -> editor.remove("layout.$id.x").remove("layout.$id.y").remove("layout.$id.scale") }
        layoutSession.saved.forEach { (id,p) -> editor.putFloat("layout.$id.x",p.x).putFloat("layout.$id.y",p.y) }
        layoutSession.savedScales.forEach { (id,scale) -> editor.putFloat("layout.$id.scale",scale) }
        editor.apply();syncLayout()
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
