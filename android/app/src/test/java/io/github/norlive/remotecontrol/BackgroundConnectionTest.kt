package io.github.norlive.remotecontrol

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=RemoteApplication::class,manifest=Config.NONE)
class BackgroundConnectionTest {
    private fun model(): RemoteViewModel {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("controller",0).edit().clear().commit()
        return RemoteViewModel(app).apply { setStickConfig(0,JoystickConfig(deadZonePercent=0)) }
    }
    @Test fun backgroundDefaultsOffAndClearsControl() {
        val vm=model()
        assertFalse(vm.keepBackground)
        vm.foreground(true);vm.axes(true,700,400)
        vm.foreground(false)
        assertEquals(0,vm.controls.lx)
        assertFalse(vm.controls.enabled)
        assertFalse(vm.retainsBackground)
    }
    @Test fun preferencePersistsWithoutClaimingServiceOwnership() {
        val vm=model();vm.updateKeepBackground(true)
        val restored=RemoteViewModel(RuntimeEnvironment.getApplication())
        assertTrue(restored.keepBackground)
        assertFalse(restored.backgroundOwned)
        assertFalse(restored.retainsBackground)
        restored.foreground(true);restored.axes(true,700,400);restored.foreground(false)
        assertEquals(0,restored.controls.lx)
    }
    @Test fun staleServiceCallbacksCannotClaimOrDisconnectController() {
        val vm=model();vm.foreground(true);vm.updateKeepBackground(true)
        vm.axes(true,700,400)
        assertFalse(vm.backgroundServiceStarted(-1))
        vm.backgroundServiceFailed(-1,"old service")
        vm.disconnectFromService(-1)
        assertEquals(700,vm.controls.lx)
        assertEquals("",vm.connectionError)
        vm.foreground(false)
    }
    @Test fun applicationStoreSharesControllerBeyondActivityOwners() {
        val app=(RuntimeEnvironment.getApplication() as RemoteApplication)
        val first=app.controller
        first.setStickConfig(0,JoystickConfig(deadZonePercent=0))
        assertSame(first,ViewModelProvider(app,ViewModelProvider.AndroidViewModelFactory(app))[RemoteViewModel::class.java])
        first.axes(true,700,400)
        assertEquals(700,app.controller.controls.lx)
        app.viewModelStore.clear()
        assertNotSame(first,app.controller)
        assertFalse(app.controller.controls.enabled)
        app.viewModelStore.clear()
    }
    @Test fun separateApplicationOwnerNeverUsesCachedFactoryApplication() {
        val app=RuntimeEnvironment.getApplication() as RemoteApplication
        ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        val other=RemoteApplication()
        android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext",android.content.Context::class.java)
            .apply { isAccessible=true }.invoke(other,app)
        try {
            assertSame(other,other.controller.getApplication<Application>())
            assertNotSame(app.controller,other.controller)
        } finally { other.viewModelStore.clear();app.viewModelStore.clear() }
    }

}
