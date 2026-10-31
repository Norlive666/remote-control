package io.github.norlive.remotecontrol

import android.Manifest
import android.bluetooth.BluetoothAdapter
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
    @Test fun fifthFailureCannotRestartBackgroundServiceOnReturnOrPreferenceToggle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app=RuntimeEnvironment.getApplication<android.app.Application>()
        app.getSharedPreferences("controller",0).edit().clear().commit()
        val vm=RemoteViewModel(app)
        try {
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            val adapter=app.getSystemService(BluetoothManager::class.java).adapter
            shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
            val device=mock(BluetoothDevice::class.java)
            vm.foreground(true);vm.setKeepBackground(true)
            vm.connect(DeviceRow("BT630","F2:E7:05:FA:F8:F2",device,viaBle=true))
            val token=RemoteViewModel::class.java.getDeclaredField("generation").apply { isAccessible=true }.getInt(vm)
            assertTrue(vm.backgroundServiceStarted(token))
            vm.foreground(false)
            runCurrent();advanceTimeBy(12_000);runCurrent()
            verify(device,times(5)).connectGatt(any(),eq(false),any(),eq(BluetoothDevice.TRANSPORT_LE))
            assertFalse(vm.connecting)
            assertFalse(vm.retainsBackground)
            assertNull(RemoteViewModel::class.java.getDeclaredField("btSession").apply { isAccessible=true }.get(vm))
            while(shadowOf(app).nextStartedService!=null) { /* discard the original authorized start */ }
            vm.foreground(true)
            vm.setKeepBackground(false);vm.setKeepBackground(true)
            assertNull("A completed engine must never acquire another service lease",shadowOf(app).nextStartedService)
            assertFalse(vm.backgroundServiceStarted(token))
        } finally {
            vm.disconnect();vm.foreground(false)
            Dispatchers.resetMain()
        }
    }

}
