package io.github.norlive.remotecontrol

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import androidx.activity.result.ActivityResultRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=RemoteApplication::class)
class MainActivityPermissionTest {
    @After fun cleanup() {
        val app=RuntimeEnvironment.getApplication() as RemoteApplication
        app.viewModelStore.clear()
        app.getSharedPreferences("controller",0).edit().clear().commit()
    }
    private fun permissionCode(registry: ActivityResultRegistry): Int = ActivityResultRegistry::class.java.declaredFields
        .map { it.apply { isAccessible=true }.get(registry) }.filterIsInstance<Map<*,*>>()
        .first { it["activity_rq#0"] is Int }["activity_rq#0"] as Int

    private fun directRequest(activity: MainActivity,row: DeviceRow?) {
        val actionType=MainActivity::class.java.declaredClasses.single { it.simpleName=="BluetoothAction" }
        val target=actionType.declaredClasses.single { it.simpleName==if(row==null)"LastDevice" else "Select" }
        val action=if(row==null)target.getDeclaredField("INSTANCE").apply { isAccessible=true }.get(null)
            else target.getDeclaredConstructor(DeviceRow::class.java).apply { isAccessible=true }.newInstance(row)
        MainActivity::class.java.getDeclaredMethod("requestBluetooth",actionType).apply { isAccessible=true }.invoke(activity,action)
    }
    private fun prepareDirectRequest(): RemoteApplication {
        val app=RuntimeEnvironment.getApplication() as RemoteApplication
        app.viewModelStore.clear()
        app.getSharedPreferences("controller",0).edit().clear()
            .putString("connection.last.name","Remembered").putString("connection.last.address","F2:E7:05:FA:F8:F2").commit()
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(app).denyPermissions(*app.controller.permissions())
        return app
    }
    private fun selectedRow(app: RemoteApplication): DeviceRow {
        val device=app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("FA:ED:FC:7D:D5:F7")
        return DeviceRow("Selected",device.address,device)
    }

    @Test fun liveDirectPermissionGrantRequestsConnectOnlyAndUsesOriginalTargetWithoutScanning() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for(selected in listOf(false,true)) {
                val app=prepareDirectRequest()
                val row=if(selected)selectedRow(app) else null
                val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
                try {
                    directRequest(activity.get(),row)
                    val request=shadowOf(activity.get()).lastRequestedPermission
                    assertNotNull("CONNECT denial must request permission",request)
                    assertEquals(setOf(Manifest.permission.BLUETOOTH_CONNECT),request.requestedPermissions.toSet())
                    assertNull(app.controller.selectedDevice);assertFalse(app.controller.connecting)
                    shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
                    activity.get().activityResultRegistry.dispatchResult(request.requestCode,mapOf(Manifest.permission.BLUETOOTH_CONNECT to true))
                    runCurrent()
                    assertEquals(if(selected)"FA:ED:FC:7D:D5:F7" else "F2:E7:05:FA:F8:F2",app.controller.selectedDevice?.address)
                    assertTrue(app.controller.connecting);assertFalse(app.controller.scanning)
                } finally { activity.pause().stop().destroy();app.viewModelStore.clear();runCurrent() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun staleDirectPermissionGrantAfterCancelReplacementOrBackgroundCannotReviveTarget() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for(selected in listOf(false,true))for(stop in listOf("cancel","replace","background")) {
                val app=prepareDirectRequest()
                val row=if(selected)selectedRow(app) else null
                val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
                try {
                    directRequest(activity.get(),row)
                    val request=shadowOf(activity.get()).lastRequestedPermission
                    assertEquals(setOf(Manifest.permission.BLUETOOTH_CONNECT),request.requestedPermissions.toSet())
                    shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
                    when(stop) {
                        "cancel" -> MainActivity::class.java.getDeclaredMethod("cancelConnection").apply { isAccessible=true }.invoke(activity.get())
                        "replace" -> {
                            val device=app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
                            app.controller.connect(DeviceRow("Replacement",device.address,device));runCurrent()
                            assertTrue(app.controller.connecting)
                        }
                        "background" -> activity.pause().stop()
                    }
                    activity.get().activityResultRegistry.dispatchResult(request.requestCode,mapOf(Manifest.permission.BLUETOOTH_CONNECT to true))
                    if(stop=="background")activity.start().resume()
                    runCurrent()
                    assertFalse("Direct callback must not scan",app.controller.scanning)
                    if(stop=="replace") {
                        assertEquals("AA:BB:CC:DD:EE:FF",app.controller.selectedDevice?.address)
                        assertTrue(app.controller.connecting)
                    } else {
                        assertNull("Stale $stop/$selected callback revived target",app.controller.selectedDevice)
                        assertFalse(app.controller.connecting);assertFalse(app.controller.connected)
                    }
                } finally { activity.pause().stop().destroy();app.viewModelStore.clear();runCurrent() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun permissionResultAfterCancelOrReplacementCannotRestartDiscovery() {
        for(replace in listOf(false,true)) {
            val app=RuntimeEnvironment.getApplication() as RemoteApplication
            app.getSharedPreferences("controller",0).edit().clear().commit()
            shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
            shadowOf(app).denyPermissions(*app.controller.permissions())
            val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                MainActivity::class.java.getDeclaredMethod("findDevices").apply { isAccessible=true }.invoke(activity.get())
                val permissions=app.controller.permissions().associateWith { true }
                if(replace) {
                    val device=app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("FA:ED:FC:7D:D5:F7")
                    app.controller.connect(DeviceRow("Replacement",device.address,device))
                } else app.controller.disconnect()
                shadowOf(app).grantPermissions(*permissions.keys.toTypedArray())
                activity.get().activityResultRegistry.dispatchResult(permissionCode(activity.get().activityResultRegistry),permissions)
                assertFalse("Cancelled or replaced requests must not restart discovery",app.controller.scanning)
            } finally { activity.pause().stop().destroy();app.viewModelStore.clear() }
        }
    }

    @Test fun foregroundPermissionGrantStillStartsRequestedDiscovery() {
        val app=RuntimeEnvironment.getApplication() as RemoteApplication
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(app).denyPermissions(*app.controller.permissions())
        val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            MainActivity::class.java.getDeclaredMethod("findDevices").apply { isAccessible=true }.invoke(activity.get())
            val permissions=app.controller.permissions().associateWith { true }
            shadowOf(app).grantPermissions(*permissions.keys.toTypedArray())
            activity.get().activityResultRegistry.dispatchResult(permissionCode(activity.get().activityResultRegistry),permissions)
            assertTrue("A live scan intent must still run after the permission dialog",app.controller.scanning)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun permissionResultAfterBackgroundCannotRestartDiscovery() {
        val app=RuntimeEnvironment.getApplication() as RemoteApplication
        app.getSharedPreferences("controller",0).edit().clear().commit()
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(app).denyPermissions(*app.controller.permissions())
        val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            MainActivity::class.java.getDeclaredMethod("findDevices").apply { isAccessible=true }.invoke(activity.get())
            val permissions=app.controller.permissions().associateWith { true }
            activity.pause().stop()
            shadowOf(app).grantPermissions(*permissions.keys.toTypedArray())
            activity.get().activityResultRegistry.dispatchResult(permissionCode(activity.get().activityResultRegistry),permissions)
            assertFalse("An expired permission result must not start discovery in the background",app.controller.scanning)
            activity.start().resume()
            assertFalse("Returning to the screen must not replay an expired request",app.controller.scanning)
        } finally { activity.pause().stop().destroy() }
    }
}
