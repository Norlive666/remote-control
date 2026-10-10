package io.github.norlive.remotecontrol

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface BluetoothTransport {
    suspend fun open(device: BluetoothDevice)
    suspend fun send(frame: ByteArray)
    fun close()
}

/** One sequential caller owns writes; closing the socket unblocks a pending write. */
@SuppressLint("MissingPermission")
class ClassicTransport : BluetoothTransport {
    @Volatile private var socket: BluetoothSocket? = null
    private var closed=false
    override suspend fun open(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        val pending = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))
        synchronized(this) {
            if(closed){pending.close();throw IOException("连接已取消")}
            socket=pending
        }
        try { pending.connect() } catch (e: Exception) { pending.close(); throw e }
    }
    override suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) {
        (socket ?: throw IOException("连接已关闭")).outputStream.write(frame)
    }
    override fun close() {
        val old=synchronized(this){closed=true;val old=socket;socket=null;old}
        runCatching { old?.close() }
    }
}

/** Nordic owns GATT operation serialization; only one control write is submitted at a time. */
@SuppressLint("MissingPermission")
class Bt630Transport(context: Context, private val lost: () -> Unit) : BleManager(context), BluetoothTransport {
    private var tx: BluetoothGattCharacteristic? = null
    init {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) { }
            override fun onDeviceConnected(device: BluetoothDevice) { }
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) { }
            override fun onDeviceReady(device: BluetoothDevice) { }
            override fun onDeviceDisconnecting(device: BluetoothDevice) { }
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) { lost() }
        })
    }
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        tx = gatt.getService(uuid(0xfff0))?.getCharacteristic(uuid(0xfff2))
        return tx?.properties?.and(BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 && tx != null
    }
    override fun initialize() { requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH).enqueue() }
    override fun onServicesInvalidated() { tx=null }
    override suspend fun open(device: BluetoothDevice): Unit = suspendCancellableCoroutine { continuation ->
        connect(device).timeout(10000).done { if(continuation.isActive) continuation.resume(Unit) }
            .fail { _, status -> if(continuation.isActive) continuation.resumeWithException(IOException("BLE 连接失败：$status")) }.enqueue()
        continuation.invokeOnCancellation { disconnect().enqueue() }
    }
    override suspend fun send(frame: ByteArray): Unit = suspendCancellableCoroutine { continuation ->
        val characteristic=tx
        if(characteristic==null) { continuation.resumeWithException(IOException("FFF2 不可用")); return@suspendCancellableCoroutine }
        val type=if(characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        writeCharacteristic(characteristic,frame,type).timeout(1000)
            .done { if(continuation.isActive) continuation.resume(Unit) }
            .fail { _, status -> if(continuation.isActive) continuation.resumeWithException(IOException("BLE 写入失败：$status")) }.enqueue()
    }
    // BleManager.close() tears down the GATT and cancels pending operations.
    private fun uuid(short: Int)=UUID.fromString("0000${short.toString(16)}-0000-1000-8000-00805f9b34fb")
}
