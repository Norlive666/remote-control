package io.github.norlive.remotecontrol

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],manifest=Config.NONE)
class PanelPersistenceTest {
    @Test fun legacyLayoutSurvivesAndCustomWidgetsConfigsAndEmptyPanelRoundTrip() {
        val app=RuntimeEnvironment.getApplication();val prefs=app.getSharedPreferences("controller",0)
        prefs.edit().clear().putFloat("layout.leftSlider.x",.37f).putFloat("layout.leftSlider.y",.12f)
            .putString("slider.0.values","20,40").putInt("gamepad.X.slider",1).putInt("gamepad.X.value",2).commit()
        val vm=RemoteViewModel(app)
        assertEquals(defaultPanelControls(),vm.panelControls)
        assertEquals(PanelPosition(.37f,.12f),vm.layoutPositions["leftSlider"])
        assertEquals(listOf(20,40),vm.sliderConfigs[0].values)
        val custom=PanelControl("custom","夹爪","按住打开",3,PanelControlType.SHORT_LONG,77,88)
        vm.savePanelControl(custom);vm.setSliderConfig(3,SliderConfig(stepped=false));vm.setGamepadBinding(GamepadButton.Y,GamepadBinding(3,725))
        val reload=RemoteViewModel(app)
        assertEquals(custom,reload.panelControls.last());assertFalse(reload.sliderConfigs[3].stepped)
        assertEquals(GamepadBinding(3,725),reload.gamepadBindings[GamepadButton.Y])
        assertEquals(PanelPosition(.37f,.12f),reload.layoutPositions["leftSlider"])
        reload.panelControls.forEach { reload.deletePanelControl(it.id) }
        assertTrue(RemoteViewModel(app).panelControls.isEmpty())
    }
    @Test fun enablePreservesButtonRawValueAndNormalizesSliderOnlyChannel() {
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("controller",0).edit().clear().commit()
        val vm=RemoteViewModel(app);vm.foreground(true)
        val control=PanelControl("custom","button",channel=3,pressValue=77)
        vm.savePanelControl(control);vm.setSliderConfig(0,SliderConfig(values=listOf(20,40)))
        vm.suspendGamepad();vm.panelPress(control.id)
        @Suppress("UNCHECKED_CAST")
        val connected=RemoteViewModel::class.java.getDeclaredField("connected\$delegate").apply { isAccessible=true }.get(vm) as androidx.compose.runtime.MutableState<Boolean>
        connected.value=true;vm.enable(true)
        assertTrue(vm.controls.enabled);assertEquals(77,vm.controls.slider4);assertEquals(20,vm.controls.slider1)
        vm.panelRelease(control.id);assertEquals(0,vm.controls.slider4);vm.foreground(false)
    }
    @Test fun settingsAndLayoutEditingCancelAllFourChannelsAndPendingLongPress() {
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("controller",0).edit().clear().commit()
        val vm=RemoteViewModel(app);vm.foreground(true)
        val control=PanelControl("custom","button",channel=3,type=PanelControlType.MOMENTARY,pressValue=800)
        vm.savePanelControl(control);vm.panelPress(control.id);assertEquals(800,vm.controls.slider4)
        vm.updateSettingsOpen(true);assertEquals(0,vm.controls.slider4)
        vm.panelPress(control.id);assertEquals(0,vm.controls.slider4)
        vm.updateSettingsOpen(false);vm.panelPress(control.id);assertEquals(800,vm.controls.slider4)
        vm.beginLayoutEdit();assertEquals(0,vm.controls.slider4);assertFalse(vm.controls.enabled)
        vm.panelRelease(control.id);assertEquals(0,vm.controls.slider4)
        vm.foreground(false)
    }
}
