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
import java.io.IOException

enum class LinkMode(val title: String, val period: Long) { HC05("HC-05 · 经典蓝牙",50), BT630("BT630 · BLE",20) }
data class DeviceRow(val name: String, val address: String, val device: BluetoothDevice)
data class FunctionKey(val label: String, val action: String)

@SuppressLint("MissingPermission")
class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences=application.getSharedPreferences("controller",0)
    private val adapter=(application.getSystemService(BluetoothManager::class.java))?.adapter
    private val main=Handler(Looper.getMainLooper())
    private val session=ControlSession()
    var controls by mutableStateOf(session.state); private set
    var mode by mutableStateOf(if(preferences.getString("mode","HC05")=="BT630")LinkMode.BT630 else LinkMode.HC05); private set
    var status by mutableStateOf("未连接"); private set
    var connected by mutableStateOf(false); private set
    var connecting by mutableStateOf(false); private set
    var scanning by mutableStateOf(false); private set
    var devices by mutableStateOf(listOf<DeviceRow>()); private set
    var lastFrame by mutableStateOf("—"); private set
    var sent by mutableLongStateOf(0); private set
    var keys by mutableStateOf(listOf(FunctionKey("连接","connect"),FunctionKey("复位","reset"),FunctionKey("急停","stop")).mapIndexed { i,k ->
        val action=preferences.getString("action$i",k.action) ?: k.action
        FunctionKey(preferences.getString("label$i",k.label) ?: k.label,if(action in actions)action else k.action)
    }); private set
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
    fun setMode(value: LinkMode) {
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
        val link=if(mode==LinkMode.HC05)ClassicTransport() else Bt630Transport(getApplication()) { main.post { if(token==generation && connected) fail("蓝牙连接已断开") } }
        transport=link;connecting=true;status="连接 ${row.name}…"
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
    fun axes(left: Boolean,x: Int,y: Int) { session.axes(left,x,y);sync() }
    fun slider(index: Int,value: Int) { session.slider(index,value);sync() }
    fun enable(value: Boolean) { if(value && (!connected || !foreground))return;session.enable(value);sync() }
    fun stop() { session.stop();sync();status=if(connected)"急停已锁定 · 持续发送归零帧" else "急停已锁定" }
    fun reset() { session.reset();sync() }
    private fun sync() { controls=session.state }
    fun setKey(index: Int,label: String,action: String) {
        if(action !in actions)return
        val key=FunctionKey(label.take(6).ifBlank { "按钮" },action)
        keys=keys.mapIndexed { i,k -> if(i==index)key else k }
        preferences.edit().putString("label$index",key.label).putString("action$index",key.action).apply()
    }
    override fun onCleared() { disconnect();super.onCleared() }
    companion object { val actions=listOf("connect","reset","stop","enable","hide") }
}
