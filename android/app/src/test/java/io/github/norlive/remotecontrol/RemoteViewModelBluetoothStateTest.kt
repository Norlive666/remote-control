package io.github.norlive.remotecontrol

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.*
import java.util.UUID
import org.junit.Before
import org.junit.After
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],manifest=Config.NONE)
class RemoteViewModelBluetoothStateTest {
    @Before fun clearPreferences() { RuntimeEnvironment.getApplication().getSharedPreferences("controller",0).edit().clear().commit() }
    @After fun clearSavedPreferences() = clearPreferences()

    private class GattFixture {
        val device=mock(BluetoothDevice::class.java)
        val gatt=mock(BluetoothGatt::class.java)
        lateinit var callback: BluetoothGattCallback
        private fun uuid(id: String)=UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")
        val service=BluetoothGattService(uuid("fff0"),BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val tx=BluetoothGattCharacteristic(uuid("fff2"),BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE)
        val rx=BluetoothGattCharacteristic(uuid("fff1"),BluetoothGattCharacteristic.PROPERTY_NOTIFY,0)
        val cccd=BluetoothGattDescriptor(uuid("2902"),BluetoothGattDescriptor.PERMISSION_WRITE)
        init {
            doAnswer { callback=it.getArgument(2);gatt }.`when`(device).connectGatt(any(),eq(false),any(),eq(BluetoothDevice.TRANSPORT_LE))
            service.addCharacteristic(tx);rx.addDescriptor(cccd);service.addCharacteristic(rx)
            `when`(gatt.discoverServices()).thenReturn(true)
            `when`(gatt.getService(uuid("fff0"))).thenReturn(service)
            `when`(gatt.setCharacteristicNotification(rx,true)).thenReturn(true)
            `when`(gatt.writeDescriptor(eq(cccd),any(ByteArray::class.java))).thenReturn(BluetoothStatusCodes.SUCCESS)
            `when`(gatt.writeCharacteristic(eq(tx),any(ByteArray::class.java),anyInt())).thenReturn(BluetoothStatusCodes.SUCCESS)
        }
        fun discover() { callback.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_CONNECTED);callback.onServicesDiscovered(gatt,0) }
        fun ready() { callback.onDescriptorWrite(gatt,cccd,0) }
    }

    @Test fun onlyReadyPersistsDeviceAndFailedReplacementCannotOverwriteIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("controller",0)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        val vm=RemoteViewModel(app)
        try {
            vm.foreground(true)
            val first=GattFixture()
            vm.connect(DeviceRow("My BT630","F2:E7:05:FA:F8:F2",first.device));runCurrent()
            first.discover();runCurrent()
            assertFalse(vm.connected)
            assertNull(prefs.getString("connection.last.address",null))
            first.ready();runCurrent()
            assertTrue(vm.connected)
            assertEquals("F2:E7:05:FA:F8:F2",prefs.getString("connection.last.address",null))
            assertEquals("My BT630",prefs.getString("connection.last.name",null))
            vm.disconnect()
            val failed=GattFixture()
            failed.service.characteristics.clear()
            vm.connect(DeviceRow("Wrong module","FA:ED:FC:7D:D5:F7",failed.device));runCurrent()
            failed.discover();runCurrent()
            assertFalse(vm.connecting);assertFalse(vm.connected)
            assertEquals("F2:E7:05:FA:F8:F2",prefs.getString("connection.last.address",null))
            assertEquals("My BT630",prefs.getString("connection.last.name",null))
            val reloaded=RemoteViewModel(app)
            assertFalse(reloaded.connected);assertFalse(reloaded.connecting);assertNull(reloaded.selectedDevice)
            verify(first.device,times(1)).connectGatt(any(),eq(false),any(),eq(BluetoothDevice.TRANSPORT_LE))
        } finally { vm.foreground(false);Dispatchers.resetMain() }
    }

    @Test fun adapterOffThenOnDuringBackoffCannotRestartOldTarget() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val app=RuntimeEnvironment.getApplication()
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            val adapter=app.getSystemService(BluetoothManager::class.java).adapter
            shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
            val vm=RemoteViewModel(app)
            val device=mock(BluetoothDevice::class.java)
            // A null platform GATT result is a transient open failure, leaving the session in backoff.
            val row=DeviceRow("BT630","F2:E7:05:FA:F8:F2",device,viaBle=true)
            vm.foreground(true)
            vm.connect(row)
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            verify(device,times(1)).connectGatt(any(),eq(false),any(),eq(BluetoothDevice.TRANSPORT_LE))
            assertEquals("GATT connection could not start",vm.connectionError)
            assertTrue(vm.connectionError,vm.connecting)
            vm.axes(true,400,300)
            shadowOf(adapter).setState(BluetoothAdapter.STATE_TURNING_OFF)
            app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_TURNING_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertFalse(vm.connected)
            assertFalse(vm.connecting)
            assertNull(vm.selectedDevice)
            assertEquals(0,vm.controls.lx)
            assertFalse(vm.controls.enabled)
            shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
            app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_ON))
            shadowOf(Looper.getMainLooper()).idle()
            advanceTimeBy(5_000)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
            runCurrent()
            verify(device,times(1)).connectGatt(any(),eq(false),any(),eq(BluetoothDevice.TRANSPORT_LE))
            assertFalse(vm.connecting)
            vm.foreground(false)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun foregroundObserverIsUnregisteredInBackground() {
        val app=RuntimeEnvironment.getApplication()
        val vm=RemoteViewModel(app)
        vm.foreground(true)
        val field=runCatching { RemoteViewModel::class.java.getDeclaredField("adapterStateReceiver") }.getOrNull()
        assertNotNull(field)
        val receiver=field!!.apply { isAccessible=true }.get(vm) as BroadcastReceiver
        vm.foreground(false)
        var unregistered=false
        try { app.unregisterReceiver(receiver) } catch(_: IllegalArgumentException) { unregistered=true }
        assertTrue("Adapter state observer must be unregistered",unregistered)
    }
    @Test fun permanentFailureCannotRestartBackgroundServiceOnReturnOrPreferenceToggle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("controller",0).edit().clear().commit()
        val vm=RemoteViewModel(app)
        try {
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            val adapter=app.getSystemService(BluetoothManager::class.java).adapter
            shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
            val device=mock(BluetoothDevice::class.java)
            doThrow(SecurityException("permission revoked")).`when`(device).connectGatt(any(),eq(false),any(),eq(BluetoothDevice.TRANSPORT_LE))
            vm.foreground(true);vm.updateKeepBackground(true)
            vm.connect(DeviceRow("BT630","F2:E7:05:FA:F8:F2",device,viaBle=true))
            val token=RemoteViewModel::class.java.getDeclaredField("serviceGeneration").apply { isAccessible=true }.get(vm) as Int
            assertTrue(vm.backgroundServiceStarted(token))
            vm.foreground(false)
            runCurrent();advanceTimeBy(12_000);runCurrent()
            verify(device,times(1)).connectGatt(any(),eq(false),any(),eq(BluetoothDevice.TRANSPORT_LE))
            assertFalse(vm.connecting)
            assertFalse(vm.retainsBackground)
            assertNull(RemoteViewModel::class.java.getDeclaredField("btSession").apply { isAccessible=true }.get(vm))
            while(shadowOf(app).nextStartedService!=null) { /* discard the original authorized start */ }
            vm.foreground(true)
            vm.updateKeepBackground(false);vm.updateKeepBackground(true)
            assertNull("A completed engine must never acquire another service lease",shadowOf(app).nextStartedService)
            assertFalse(vm.backgroundServiceStarted(token))
        } finally {
            vm.disconnect();vm.foreground(false)
            Dispatchers.resetMain()
        }
    }

}
