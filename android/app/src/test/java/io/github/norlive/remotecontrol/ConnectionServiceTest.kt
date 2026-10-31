package io.github.norlive.remotecontrol

import android.content.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
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
    @Test fun activeSenderKeepsEnabledValuesAndSequenceAcrossBackgroundAndReturn() = runTest {
        val vm=prepare()
        val state=RemoteViewModel::class.java.getDeclaredField("session").apply { isAccessible=true }.get(vm) as ControlSession
        val frames=mutableListOf<ByteArray>()
        var sequence=0
        val engine=Bt630Session(backgroundScope,
            linkFactory={ object: Bt630Session.Link {
                override suspend fun open() {}
                override suspend fun send(frame: ByteArray) { frames.add(frame) }
                override fun close() {}
            } },frame={ControlProtocol.encode(state.state,sequence++)},pauseControls={vm.suspendGamepad()},stateChanged={},sent={})
        field(vm,"btSession",engine)
        val service=Robolectric.buildService(ConnectionService::class.java).create()
        service.get().onStartCommand(intent(),0,1)
        engine.start();runCurrent()
        state.enable(true);vm.axes(true,700,400);vm.slider(0,2)
        val before=frames.size
        vm.foreground(false)
        advanceTimeBy(100);runCurrent()
        assertTrue(frames.size>before)
        val retained=frames.last()
        assertEquals(20,retained.size)
        assertEquals(1,retained[17].toInt() and 1)
        assertEquals(700,(retained[4].toInt() and 255) or ((retained[5].toInt() and 255) shl 8))
        assertEquals(2,vm.controls.slider1)
        assertSame(vm,RuntimeEnvironment.getApplication<RemoteApplication>().controller)
        vm.foreground(true)
        assertTrue(vm.controls.enabled)
        advanceTimeBy(40);runCurrent()
        frames.zipWithNext().forEach { (a,b) -> assertEquals((a[3].toInt()+1) and 255,b[3].toInt() and 255) }
        vm.disconnect()
        val stopped=frames.size
        advanceTimeBy(100);runCurrent()
        assertEquals(stopped,frames.size)
        service.destroy();vm.foreground(false)
    }

    @Test fun staleStartIntentCannotStopCurrentServiceLease() {
        val vm=prepare()
        val controller=Robolectric.buildService(ConnectionService::class.java).create()
        val service=controller.get()
        service.onStartCommand(intent(),0,1)
        vm.disconnect()
        field(vm,"btSession",mock(Bt630Session::class.java));field(vm,"serviceGeneration",1)
        service.onStartCommand(intent(1),0,2)
        assertTrue(vm.retainsBackground)
        service.onStartCommand(intent(0),0,3)
        assertTrue(vm.retainsBackground)
        assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf)
        vm.disconnect();controller.destroy();vm.foreground(false)
    }

    @Test fun releasedPulseSurvivesPassiveUiDisposalButStillExpires() {
        val vm=prepare()
        vm.savePanelControl(PanelControl("pulse","pulse",channel=3,type=PanelControlType.SHORT_LONG,pressValue=77))
        assertTrue(vm.backgroundServiceStarted(0))
        vm.panelPress("pulse");vm.panelRelease("pulse")
        assertEquals(77,vm.controls.slider4)
        vm.foreground(false);vm.panelDispose("pulse")
        assertEquals(77,vm.controls.slider4)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(0,vm.controls.slider4)
        vm.disconnect()
    }

    @Test fun recreatedGamepadOwnerKeepsPhysicalIdentityForBackgroundRemoval() {
        val vm=prepare()
        assertTrue(vm.backgroundServiceStarted(0))
        vm.gamepadDevice("physical",7);vm.axes(true,700,400)
        val input=GamepadInput(RuntimeEnvironment.getApplication(),vm)
        val manager=mock(android.hardware.input.InputManager::class.java)
        val device=mock(android.view.InputDevice::class.java)
        org.mockito.Mockito.`when`(manager.inputDeviceIds).thenReturn(intArrayOf(7))
        org.mockito.Mockito.`when`(manager.getInputDevice(7)).thenReturn(device)
        org.mockito.Mockito.`when`(device.id).thenReturn(7)
        org.mockito.Mockito.`when`(device.name).thenReturn("physical")
        org.mockito.Mockito.`when`(device.supportsSource(android.view.InputDevice.SOURCE_GAMEPAD)).thenReturn(true)
        GamepadInput::class.java.getDeclaredField("manager").apply { isAccessible=true }.set(input,manager)
        input.start()
        assertEquals(7,vm.gamepadDeviceId)
        input.stop();vm.foreground(false)
        val observer=RemoteViewModel::class.java.getDeclaredField("inputObserver").apply { isAccessible=true }.get(vm) as android.hardware.input.InputManager.InputDeviceListener
        observer.onInputDeviceRemoved(7)
        assertEquals(0,vm.controls.lx)
        assertNull(vm.gamepadDeviceId)
        vm.disconnect()
    }

}
