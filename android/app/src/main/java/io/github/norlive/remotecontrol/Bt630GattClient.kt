package io.github.norlive.remotecontrol

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

// Native GATT flow adapted from bt_mcu, commit 10dddc6 (Mulan PSL v2); see licenses/bt-mcu.txt.
class Bt630GattClient(
    context: Context,
    private val device: BluetoothDevice,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    private val lost: (String) -> Unit,
    private val received: (ByteArray) -> Unit,
) : Bt630Session.Link {
    private val context = context.applicationContext
    private val guard = Any()
    private val writes = Mutex()
    private var generation = 0L
    private var gatt: BluetoothGatt? = null
    private var phase = Phase.CLOSED
    private var opening: CompletableDeferred<Unit>? = null
    private var writing: CompletableDeferred<Unit>? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var cccd: BluetoothGattDescriptor? = null

    private enum class Phase { CLOSED, CONNECTING, DISCOVERING, SUBSCRIBING, READY }

    override suspend fun open() {
        close()
        val pending = CompletableDeferred<Unit>()
        val token: Long
        synchronized(guard) {
            token = ++generation
            opening = pending
            phase = Phase.CONNECTING
        }
        try {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                throw Bt630Failure("Bluetooth connect permission missing", false)
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled != true) throw Bt630Failure("Bluetooth is disabled", false)
            val callback = callback(token)
            val connection = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw Bt630Failure("GATT connection could not start")
            synchronized(guard) {
                if (generation == token) gatt = connection else dispose(connection)
            }
            try { withTimeout(10_000) { pending.await() } }
            catch (_: TimeoutCancellationException) { throw Bt630Failure("GATT connection timed out (10 s)") }
        } catch (error: Exception) {
            closeCurrent(token)
            throw error
        }
    }

    override suspend fun send(frame: ByteArray) = writes.withLock {
        val pending = CompletableDeferred<Unit>()
        val connection: BluetoothGatt
        val characteristic: BluetoothGattCharacteristic
        val token: Long
        synchronized(guard) {
            connection = gatt ?: throw Bt630Failure("GATT is closed")
            if (phase != Phase.READY) throw Bt630Failure("GATT is not ready")
            characteristic = tx ?: throw Bt630Failure("FFF2 is missing", false)
            token = generation
            writing = pending
        }
        try {
            val started = synchronized(guard) {
                if (generation != token || phase != Phase.READY || gatt !== connection)
                    throw Bt630Failure("GATT is closed")
                val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                if (Build.VERSION.SDK_INT >= 33) {
                    connection.writeCharacteristic(characteristic, frame.copyOf(), type) == BluetoothStatusCodes.SUCCESS
                } else {
                    characteristic.writeType = type
                    characteristic.value = frame.copyOf()
                    connection.writeCharacteristic(characteristic)
                }
            }
            if (!started) throw Bt630Failure("GATT write could not start")
            try { withTimeout(1_000) { pending.await() } }
            catch (_: TimeoutCancellationException) { throw Bt630Failure("GATT write timed out (1 s)") }
        } catch (error: Exception) {
            closeCurrent(token)
            throw error
        } finally {
            synchronized(guard) { if (writing === pending) writing = null }
        }
    }

    override fun close() {
        val old: BluetoothGatt?
        synchronized(guard) {
            generation++
            phase = Phase.CLOSED
            opening?.completeExceptionally(Bt630Failure("GATT closed"))
            writing?.completeExceptionally(Bt630Failure("GATT closed"))
            opening = null
            writing = null
            tx = null
            rx = null
            cccd = null
            old = gatt
            gatt = null
        }
        old?.let(::dispose)
    }

    private fun dispose(connection: BluetoothGatt) {
        try { connection.disconnect() } catch (_: SecurityException) { }
        try { connection.close() } catch (_: SecurityException) { }
    }

    private fun closeCurrent(token: Long) = synchronized(guard) {
        if (generation == token) close()
    }

    private fun fail(token: Long, connection: BluetoothGatt, reason: String) {
        val wasReady: Boolean
        synchronized(guard) {
            if (generation != token || gatt !== connection) return
            wasReady = phase == Phase.READY
            opening?.completeExceptionally(Bt630Failure(reason))
            writing?.completeExceptionally(Bt630Failure(reason))
        }
        closeCurrent(token)
        if (wasReady) lost(reason)
    }

    private fun callback(token: Long) = object : BluetoothGattCallback() {
        private fun current(connection: BluetoothGatt) = generation == token && gatt === connection && phase != Phase.CLOSED

        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
            synchronized(guard) {
                if (!current(connection)) return
                if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                    fail(token, connection, "GATT connection status $status, state $newState")
                } else if (phase == Phase.CONNECTING) {
                    phase = Phase.DISCOVERING
                    if (!connection.discoverServices()) fail(token, connection, "GATT discoverServices could not start")
                }
            }
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            synchronized(guard) {
                if (!current(connection) || phase != Phase.DISCOVERING) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(token, connection, "GATT discovery status $status")
                    return
                }
                val service = connection.getService(SERVICE)
                val output = service?.getCharacteristic(WRITE)
                if (output == null || output.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                    opening?.completeExceptionally(Bt630Failure("BT630 FFF0/FFF2 write characteristic missing", false))
                    close()
                    return
                }
                tx = output
                val input = service.getCharacteristic(NOTIFY)
                if (input == null) {
                    phase = Phase.READY
                    opening?.complete(Unit)
                    return
                }
                val property = if (input.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else if (input.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else {
                    opening?.completeExceptionally(Bt630Failure("FFF1 cannot notify", false))
                    close()
                    return
                }
                val descriptor = input.getDescriptor(CCCD)
                if (descriptor == null) {
                    opening?.completeExceptionally(Bt630Failure("FFF1 CCCD missing", false))
                    close()
                    return
                }
                rx = input
                cccd = descriptor
                phase = Phase.SUBSCRIBING
                if (!connection.setCharacteristicNotification(input, true)) {
                    fail(token, connection, "GATT notification setup failed")
                    return
                }
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    connection.writeDescriptor(descriptor, property) == BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = property
                    connection.writeDescriptor(descriptor)
                }
                if (!started) fail(token, connection, "GATT CCCD write could not start")
            }
        }

        override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            synchronized(guard) {
                if (!current(connection) || phase != Phase.SUBSCRIBING || descriptor !== cccd) return
                if (status != BluetoothGatt.GATT_SUCCESS) fail(token, connection, "GATT CCCD status $status")
                else { phase = Phase.READY; opening?.complete(Unit) }
            }
        }

        override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            synchronized(guard) {
                if (!current(connection) || phase != Phase.READY || characteristic !== tx) return
                if (status != BluetoothGatt.GATT_SUCCESS) fail(token, connection, "GATT write status $status")
                else writing?.complete(Unit)
            }
        }

        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            synchronized(guard) {
                if (current(connection) && phase == Phase.READY && characteristic === rx) received(value.copyOf())
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            synchronized(guard) {
                if (current(connection) && phase == Phase.READY && characteristic === rx)
                    characteristic.value?.let { received(it.copyOf()) }
            }
        }
    }

    private companion object {
        fun uuid(id: String): UUID = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")
        val SERVICE = uuid("fff0")
        val WRITE = uuid("fff2")
        val NOTIFY = uuid("fff1")
        val CCCD = uuid("2902")
    }
}
