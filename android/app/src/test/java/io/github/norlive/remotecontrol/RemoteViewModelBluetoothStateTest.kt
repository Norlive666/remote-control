package io.github.norlive.remotecontrol

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
            val vm=RemoteViewModel(app)
            val adapter=mock(BluetoothAdapter::class.java)
            `when`(adapter.isEnabled).thenReturn(true)
            RemoteViewModel::class.java.getDeclaredField("adapter").apply { isAccessible=true }.set(vm,adapter)
            val row=DeviceRow("BT630","F2:E7:05:FA:F8:F2",mock(BluetoothDevice::class.java),viaBle=true)
            vm.foreground(true)
            mockConstruction(Bt630GattClient::class.java) { client,_ ->
                runBlocking { doThrow(Bt630Failure("temporary")).`when`(client).open() }
            }.use { construction ->
                vm.connect(row)
                runCurrent()
                assertEquals(1,construction.constructed().size)
                assertTrue(vm.connecting)
                vm.axes(true,400,300)
                app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_TURNING_OFF))
                shadowOf(Looper.getMainLooper()).idle()
                runCurrent()
                assertFalse(vm.connected)
                assertFalse(vm.connecting)
                assertNull(vm.selectedDevice)
                assertEquals(0,vm.controls.lx)
                assertFalse(vm.controls.enabled)
                app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_ON))
                shadowOf(Looper.getMainLooper()).idle()
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(1,construction.constructed().size)
                assertFalse(vm.connecting)
            }
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
}
