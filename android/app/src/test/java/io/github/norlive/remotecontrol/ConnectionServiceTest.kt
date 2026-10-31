package io.github.norlive.remotecontrol

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=RemoteApplication::class,manifest=Config.NONE)
class ConnectionServiceTest {
    private fun prepare(): RemoteViewModel {
        val app=RuntimeEnvironment.getApplication<RemoteApplication>()
        app.getSharedPreferences("controller",0).edit().clear().commit()
        val vm=app.controller
        vm.setStickConfig(0,JoystickConfig(deadZonePercent=0))
        vm.foreground(true);vm.setKeepBackground(true)
        field(vm,"btSession",mock(Bt630Session::class.java))
        field(vm,"serviceGeneration",0)
        return vm
    }
    private fun field(vm: RemoteViewModel,name: String,value: Any?) {
        RemoteViewModel::class.java.getDeclaredField(name).apply { isAccessible=true }.set(vm,value)
    }
    private fun intent(token: Int=0)=Intent(RuntimeEnvironment.getApplication(),ConnectionService::class.java).putExtra("generation",token)
    @Test fun confirmedServicePreservesValuesAndNotificationDisconnectClearsThem() {
        val vm=prepare()
        val service=Robolectric.buildService(ConnectionService::class.java).create().get()
        assertEquals(android.app.Service.START_NOT_STICKY,service.onStartCommand(intent(),0,1))
        assertTrue(vm.retainsBackground)
        vm.axes(true,700,400);vm.slider(0,2)
        vm.foreground(false)
        assertEquals(700,vm.controls.lx)
        assertEquals(2,vm.controls.slider1)
        assertTrue(vm.backgroundOwned)
        service.onStartCommand(intent().setAction("disconnect"),0,2)
        assertFalse(vm.retainsBackground)
        assertEquals(0,vm.controls.lx)
        assertFalse(vm.controls.enabled)
        service.onDestroy()
    }
    @Test fun unexpectedServiceDestructionClearsActiveControls() {
        val vm=prepare()
        val controller=Robolectric.buildService(ConnectionService::class.java).create()
        controller.get().onStartCommand(intent(),0,1)
        assertTrue(vm.retainsBackground)
        vm.axes(true,700,400);vm.foreground(false)
        controller.destroy()
        assertFalse(vm.backgroundOwned)
        assertEquals(0,vm.controls.lx)
        assertTrue(vm.connectionError.contains("后台服务"))
    }
    @Test fun startupFailureAndLateCallbacksDoNotReviveConnection() {
        val vm=prepare()
        vm.axes(true,700,400)
        vm.backgroundServiceFailed(0,"denied")
        assertEquals(0,vm.controls.lx)
        assertFalse(vm.backgroundServiceStarted(0))
        assertFalse(vm.backgroundOwned)
        assertTrue(vm.connectionError.contains("denied"))
        vm.foreground(false)
    }
    @Test fun disablingPreferenceRevokesLeaseAndBackgroundConnection() {
        val vm=prepare()
        assertTrue(vm.backgroundServiceStarted(0))
        vm.axes(true,700,400);vm.foreground(false)
        vm.setKeepBackground(false)
        assertFalse(vm.backgroundOwned)
        assertEquals(0,vm.controls.lx)
        assertNull(vm.selectedDevice)
    }
    @Test fun lateOldServiceDestructionCannotStopNewLease() {
        val vm=prepare()
        val old=Robolectric.buildService(ConnectionService::class.java).create()
        old.get().onStartCommand(intent(),0,1)
        vm.disconnect()
        field(vm,"btSession",mock(Bt630Session::class.java));field(vm,"serviceGeneration",1)
        assertTrue(vm.backgroundServiceStarted(1))
        vm.axes(true,700,400)
        old.destroy()
        assertTrue(vm.retainsBackground)
        assertEquals(700,vm.controls.lx)
        vm.disconnect();vm.foreground(false)
    }
}
