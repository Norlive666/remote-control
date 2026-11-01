package io.github.norlive.remotecontrol

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
