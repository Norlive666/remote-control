package io.github.norlive.remotecontrol

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Bt630GattClientTest {
    private fun uuid(id: String) = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")

    private inner class Fixture(scope: TestScope, notify: Boolean = true, properties: Int = BluetoothGattCharacteristic.PROPERTY_WRITE) {
        val context = mock(Context::class.java)
        val device = mock(BluetoothDevice::class.java)
        val gatt = mock(BluetoothGatt::class.java)
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        lateinit var callback: BluetoothGattCallback
        val service = BluetoothGattService(uuid("fff0"), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val tx = BluetoothGattCharacteristic(uuid("fff2"), properties, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val rx = BluetoothGattCharacteristic(uuid("fff1"), BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        val cccd = BluetoothGattDescriptor(uuid("2902"), BluetoothGattDescriptor.PERMISSION_WRITE)
        val losses = mutableListOf<String>()
        val received = mutableListOf<ByteArray>()
        val client: Bt630GattClient
        init {
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.getSystemService(BluetoothManager::class.java)).thenReturn(manager)
            `when`(manager.adapter).thenReturn(adapter)
            `when`(adapter.isEnabled).thenReturn(true)
            doAnswer { call -> callback = call.getArgument(2); gatt }.`when`(device)
                .connectGatt(eq(context), eq(false), any(BluetoothGattCallback::class.java), eq(BluetoothDevice.TRANSPORT_LE))
            `when`(gatt.discoverServices()).thenReturn(true)
            `when`(gatt.getService(uuid("fff0"))).thenReturn(service)
            service.addCharacteristic(tx)
            if (notify) { rx.addDescriptor(cccd); service.addCharacteristic(rx) }
            `when`(gatt.setCharacteristicNotification(rx, true)).thenReturn(true)
            if (Build.VERSION.SDK_INT >= 33) {
                `when`(gatt.writeDescriptor(eq(cccd), any(ByteArray::class.java))).thenReturn(BluetoothStatusCodes.SUCCESS)
                `when`(gatt.writeCharacteristic(eq(tx), any(ByteArray::class.java), anyInt())).thenReturn(BluetoothStatusCodes.SUCCESS)
            }
            client = Bt630GattClient(context, device, scope, { losses += it }, { received += it })
        }
        fun connected() = callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        fun discovered() = callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        fun subscribed() = callback.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_SUCCESS)
    }

    @Test fun readinessRequiresDiscoveryAndSuccessfulCccdCallbackInOrder() = runTest {
        val f = Fixture(this)
        val opening = async { runCatching { f.client.open() } }; runCurrent()
        verify(f.device).connectGatt(eq(f.context), eq(false), any(BluetoothGattCallback::class.java), eq(BluetoothDevice.TRANSPORT_LE))
        verify(f.gatt, never()).discoverServices()
        f.connected(); runCurrent(); verify(f.gatt).discoverServices()
        assertFalse(opening.isCompleted)
        f.discovered(); runCurrent()
        verify(f.gatt).setCharacteristicNotification(f.rx, true)
        verify(f.gatt).writeDescriptor(f.cccd, byteArrayOf(1, 0))
        assertFalse(opening.isCompleted)
        f.subscribed(); runCurrent(); assertTrue(opening.await().isSuccess)
        f.client.close()
    }

    @Test fun missingFff0OrFff2OrWritePropertyIsPermanent() = runTest {
        for (missing in 0..2) {
            val f = Fixture(this, notify = false, properties = if (missing == 2) BluetoothGattCharacteristic.PROPERTY_READ else BluetoothGattCharacteristic.PROPERTY_WRITE)
            if (missing == 0) `when`(f.gatt.getService(uuid("fff0"))).thenReturn(null)
            if (missing == 1) f.service.characteristics.clear()
            val opening = async { runCatching { f.client.open() } }; runCurrent()
            f.connected(); runCurrent(); f.discovered(); runCurrent()
            val error = opening.await().exceptionOrNull()
            assertTrue(error is Bt630Failure)
            assertFalse((error as Bt630Failure).retryable)
            verify(f.gatt).close()
        }
    }

    @Test fun discoveryStartFailureAndCallbackStatusesFailAndClose() = runTest {
        for (phase in 0..3) {
            val f = Fixture(this)
            if (phase == 0) `when`(f.gatt.discoverServices()).thenReturn(false)
            val opening = async { runCatching { f.client.open() } }; runCurrent()
            if (phase == 1) f.callback.onConnectionStateChange(f.gatt, 133, BluetoothProfile.STATE_CONNECTED)
            else {
                f.connected(); runCurrent()
                if (phase == 2) f.callback.onServicesDiscovered(f.gatt, 133)
                if (phase == 3) { f.discovered(); runCurrent(); f.callback.onDescriptorWrite(f.gatt, f.cccd, 133) }
            }
            runCurrent()
            val error = opening.await().exceptionOrNull()
            assertTrue(error is Bt630Failure)
            assertTrue((error as Bt630Failure).retryable)
            assertTrue(error.message!!.contains(if (phase == 0) "discover" else "133"))
            verify(f.gatt).close()
        }
    }

    @Test fun staleGattAndPrematureDescriptorCallbackCannotPublishReady() = runTest {
        val f = Fixture(this)
        val opening = async { runCatching { f.client.open() } }; runCurrent()
        val stale = mock(BluetoothGatt::class.java)
        f.callback.onConnectionStateChange(stale, 0, BluetoothProfile.STATE_CONNECTED)
        f.callback.onServicesDiscovered(stale, 0)
        f.subscribed(); runCurrent()
        assertFalse(opening.isCompleted)
        verify(f.gatt, never()).writeDescriptor(eq(f.cccd), any(ByteArray::class.java))
        f.connected(); runCurrent(); f.discovered(); runCurrent()
        f.callback.onDescriptorWrite(stale, f.cccd, 0); runCurrent()
        assertFalse(opening.isCompleted)
        f.subscribed(); runCurrent(); assertTrue(opening.await().isSuccess)
        f.client.close()
        f.callback.onConnectionStateChange(f.gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        f.callback.onCharacteristicChanged(f.gatt, f.rx, byteArrayOf(9)); runCurrent()
        assertTrue(f.losses.isEmpty()); assertTrue(f.received.isEmpty())
    }

    @Test fun writeWaitsForMatchingCallbackAndUsesResponseWhenAvailable() = runTest {
        val f = Fixture(this, notify = false, properties = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
        val opening = async { f.client.open() }; runCurrent(); f.connected(); runCurrent(); f.discovered(); runCurrent(); opening.await()
        val first = async { runCatching { f.client.send(byteArrayOf(1)) } }
        val second = async { runCatching { f.client.send(byteArrayOf(2)) } }; runCurrent()
        verify(f.gatt).writeCharacteristic(f.tx, byteArrayOf(1), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        verify(f.gatt, never()).writeCharacteristic(f.tx, byteArrayOf(2), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        f.callback.onCharacteristicWrite(f.gatt, f.rx, 0); runCurrent(); assertFalse(first.isCompleted)
        f.callback.onCharacteristicWrite(f.gatt, f.tx, 0); runCurrent(); assertTrue(first.await().isSuccess)
        verify(f.gatt).writeCharacteristic(f.tx, byteArrayOf(2), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        f.callback.onCharacteristicWrite(f.gatt, f.tx, 133); runCurrent()
        assertTrue(second.await().exceptionOrNull() is Bt630Failure)
        verify(f.gatt).close()
    }

    @Test fun noResponseWriteStillWaitsForCompletionAndTimeoutClosesGatt() = runTest {
        val f = Fixture(this, notify = false, properties = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
        val opening = async { f.client.open() }; runCurrent(); f.connected(); runCurrent(); f.discovered(); runCurrent(); opening.await()
        val writing = async { runCatching { f.client.send(byteArrayOf(1)) } }; runCurrent()
        verify(f.gatt).writeCharacteristic(f.tx, byteArrayOf(1), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        advanceTimeBy(999); runCurrent(); assertFalse(writing.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertTrue(writing.await().exceptionOrNull() is Bt630Failure)
        verify(f.gatt).close()
    }

    @Test fun cancellationAndTenSecondConnectionTimeoutCloseGatt() = runTest {
        val f = Fixture(this)
        val opening = async { runCatching { f.client.open() } }; runCurrent()
        advanceTimeBy(9_999); runCurrent(); assertFalse(opening.isCompleted)
        advanceTimeBy(1); runCurrent(); assertTrue(opening.await().exceptionOrNull() is Bt630Failure)
        verify(f.gatt).close()
        val cancelled = Fixture(this)
        val job = async { cancelled.client.open() }; runCurrent(); job.cancel(); runCurrent()
        verify(cancelled.gatt).disconnect(); verify(cancelled.gatt).close()
    }

    @Test fun explicitCloseCancelsPendingWriteAndRejectsLateCallbacks() = runTest {
        val f = Fixture(this, notify = false)
        val opening = async { f.client.open() }; runCurrent(); f.connected(); runCurrent(); f.discovered(); runCurrent(); opening.await()
        val writing = async { runCatching { f.client.send(byteArrayOf(1)) } }; runCurrent()
        f.client.close(); runCurrent()
        assertTrue(writing.isCompleted)
        f.callback.onCharacteristicWrite(f.gatt, f.tx, 0)
        f.callback.onConnectionStateChange(f.gatt, 133, BluetoothProfile.STATE_DISCONNECTED); runCurrent()
        assertTrue(f.losses.isEmpty())
        verify(f.gatt, times(1)).close()
    }

    @Test fun subscriptionStartFailuresNeverPublishReady() = runTest {
        for (stage in 0..2) {
            val f = Fixture(this)
            if (stage == 0) `when`(f.gatt.setCharacteristicNotification(f.rx, true)).thenReturn(false)
            if (stage == 1) `when`(f.gatt.writeDescriptor(eq(f.cccd), any(ByteArray::class.java))).thenReturn(BluetoothStatusCodes.ERROR_UNKNOWN)
            if (stage == 2) f.rx.descriptors.clear()
            val opening = async { runCatching { f.client.open() } }; runCurrent()
            f.connected(); runCurrent(); f.discovered(); runCurrent()
            val error = opening.await().exceptionOrNull()
            assertTrue(error is Bt630Failure)
            assertEquals(stage != 2, (error as Bt630Failure).retryable)
            verify(f.gatt).close()
        }
    }

    @Test fun writeStartErrorFailsImmediatelyAndCloses() = runTest {
        val f = Fixture(this, notify = false)
        val opening = async { f.client.open() }; runCurrent(); f.connected(); runCurrent(); f.discovered(); runCurrent(); opening.await()
        `when`(f.gatt.writeCharacteristic(eq(f.tx), any(ByteArray::class.java), anyInt())).thenReturn(BluetoothStatusCodes.ERROR_UNKNOWN)
        val error = runCatching { f.client.send(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue(error is Bt630Failure)
        verify(f.gatt).close()
    }

    @Test fun sameClientReopenRejectsOldCallbackEvenIfAndroidReusesGattObject() = runTest {
        val f = Fixture(this, notify = false)
        val first = async { f.client.open() }; runCurrent(); f.connected(); runCurrent(); f.discovered(); runCurrent(); first.await()
        val oldCallback = f.callback
        f.client.close()
        val second = async { runCatching { f.client.open() } }; runCurrent()
        oldCallback.onConnectionStateChange(f.gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        oldCallback.onServicesDiscovered(f.gatt, 0); runCurrent()
        assertFalse(second.isCompleted)
        f.connected(); runCurrent(); f.discovered(); runCurrent()
        assertTrue(second.await().isSuccess)
        assertTrue(f.losses.isEmpty())
        f.client.close()
    }

    @Test fun notificationsAcceptOnlyCurrentFff1AndCopyCallbackValue() = runTest {
        val f = Fixture(this)
        val opening = async { f.client.open() }; runCurrent(); f.connected(); runCurrent(); f.discovered(); runCurrent(); f.subscribed(); runCurrent(); opening.await()
        f.callback.onCharacteristicChanged(f.gatt, f.tx, byteArrayOf(1))
        val bytes = byteArrayOf(2)
        f.callback.onCharacteristicChanged(f.gatt, f.rx, bytes)
        bytes[0] = 3
        runCurrent()
        assertEquals(1, f.received.size)
        assertArrayEquals(byteArrayOf(2), f.received.single())
        f.client.close()
    }

    @Test @Config(sdk = [28]) fun legacyApiUsesDescriptorAndCharacteristicValues() = runTest {
        val f = Fixture(this)
        `when`(f.gatt.writeDescriptor(f.cccd)).thenReturn(true)
        `when`(f.gatt.writeCharacteristic(f.tx)).thenReturn(true)
        val opening = async { f.client.open() }; runCurrent(); f.connected(); runCurrent(); f.discovered(); runCurrent()
        verify(f.gatt).writeDescriptor(f.cccd)
        assertArrayEquals(byteArrayOf(1, 0), f.cccd.value)
        f.subscribed(); runCurrent(); opening.await()
        val writing = async { f.client.send(byteArrayOf(7)) }; runCurrent()
        verify(f.gatt).writeCharacteristic(f.tx)
        assertArrayEquals(byteArrayOf(7), f.tx.value)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, f.tx.writeType)
        f.callback.onCharacteristicWrite(f.gatt, f.tx, 0); runCurrent(); writing.await()
        f.client.close()
    }

    @Test @Config(sdk = [28]) fun legacyNotificationDeliversCopiedFff1Only() = runTest {
        val f = Fixture(this)
        `when`(f.gatt.writeDescriptor(f.cccd)).thenReturn(true)
        val opening = async { f.client.open() }; runCurrent(); f.connected(); runCurrent(); f.discovered(); runCurrent(); f.subscribed(); runCurrent(); opening.await()
        f.tx.value = byteArrayOf(1)
        f.callback.onCharacteristicChanged(f.gatt, f.tx)
        val bytes = byteArrayOf(2)
        f.rx.value = bytes
        f.callback.onCharacteristicChanged(f.gatt, f.rx)
        bytes[0] = 3
        f.callback.onCharacteristicChanged(mock(BluetoothGatt::class.java), f.rx)
        assertEquals(1, f.received.size)
        assertArrayEquals(byteArrayOf(2), f.received.single())
        f.client.close()
    }

    @Test fun disconnectPermissionFailureStillClosesGatt() = runTest {
        val f = Fixture(this, notify = false)
        val opening = async { f.client.open() }; runCurrent(); f.connected(); runCurrent(); f.discovered(); runCurrent(); opening.await()
        doThrow(SecurityException("revoked")).`when`(f.gatt).disconnect()
        f.client.close()
        verify(f.gatt).close()
    }

    @Test fun lateConnectResultStillClosesWhenDisconnectPermissionWasRevoked() = runTest {
        val f = Fixture(this)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            f.gatt
        }.`when`(f.device).connectGatt(eq(f.context), eq(false), any(BluetoothGattCallback::class.java), eq(BluetoothDevice.TRANSPORT_LE))
        doThrow(SecurityException("revoked")).`when`(f.gatt).disconnect()
        val opening = async(Dispatchers.Default) { runCatching { f.client.open() } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        f.client.close()
        release.countDown()
        opening.await()
        verify(f.gatt).close()
    }

    @Test fun closeCannotPassAWriteBetweenValidationAndPlatformCall() = runTest {
        val f = Fixture(this, notify = false)
        val characteristic = mock(BluetoothGattCharacteristic::class.java)
        `when`(characteristic.uuid).thenReturn(uuid("fff2"))
        val reads = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer {
            if (reads.incrementAndGet() > 1) { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
            BluetoothGattCharacteristic.PROPERTY_WRITE
        }.`when`(characteristic).properties
        f.service.characteristics.clear(); f.service.addCharacteristic(characteristic)
        `when`(f.gatt.writeCharacteristic(eq(characteristic), any(ByteArray::class.java), anyInt())).thenReturn(BluetoothStatusCodes.SUCCESS)
        val opening = async { f.client.open() }; runCurrent(); f.connected(); runCurrent(); f.discovered(); runCurrent(); opening.await()
        val sending = async(Dispatchers.Default) { runCatching { f.client.send(byteArrayOf(1)) } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closeStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closing = async(Dispatchers.Default) {
            closeStarted.countDown()
            try { f.client.close() } finally { closeFinished.countDown() }
        }
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
        assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        closing.await(); sending.await()
        verify(f.gatt).writeCharacteristic(eq(characteristic), any(ByteArray::class.java), anyInt())
    }

    @Test fun missingPermissionAndDisabledAdapterArePermanent() = runTest {
        for (permission in listOf(true, false)) {
            val f = Fixture(this)
            if (permission) `when`(f.context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)).thenReturn(PackageManager.PERMISSION_DENIED)
            else `when`(f.adapter.isEnabled).thenReturn(false)
            val error = runCatching { f.client.open() }.exceptionOrNull()
            assertTrue(error is Bt630Failure)
            assertFalse((error as Bt630Failure).retryable)
            verify(f.device, never()).connectGatt(eq(f.context), eq(false), any(BluetoothGattCallback::class.java), eq(BluetoothDevice.TRANSPORT_LE))
        }
    }
}
