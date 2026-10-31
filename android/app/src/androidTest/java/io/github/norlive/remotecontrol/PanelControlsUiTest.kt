package io.github.norlive.remotecontrol

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import java.io.FileOutputStream
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PanelControlsUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun model()=ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
    private fun reset() {
        model().apply {
            updateSettingsOpen(false);cancelLayoutEdit()
            panelControls.toList().forEach { deletePanelControl(it.id) };defaultPanelControls().forEach(::savePanelControl)
            repeat(4) { setSliderConfig(it,SliderConfig()) }
            beginLayoutEdit();defaultLayoutDraft();saveLayoutEdit()
        }
    }
    @Before fun setup() { compose.runOnIdle { reset() } }
    @After fun cleanup() { compose.runOnIdle { reset() } }
    @Test fun recreatingActivityWithSettingsOpenKeepsVisibleDialogAndCanResumeInput() {
        compose.onNodeWithTag("panel-settings").performClick()
        compose.runOnIdle { assertTrue(model().settingsOpen) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("settings-done").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertFalse(model().settingsOpen)
            model().slider(0,1000);assertEquals(2,model().controls.slider1)
            model().axes(true,700,0);assertTrue(model().controls.lx>0)
        }
    }
    @Test fun exportActualPanelWithCustomControlNotes() {
        val controls=listOf(
            PanelControl("shot-momentary","夹爪","按住打开 · 松手归零",2,PanelControlType.MOMENTARY,77),
            PanelControl("shot-short-long","动作","短按点动 · 长按保持",3,PanelControlType.SHORT_LONG,1,2),
            PanelControl("shot-cycle","模式","点击切换 0 / 1 / 2",2,PanelControlType.CYCLE))
        compose.runOnIdle {
            controls.forEach { model().savePanelControl(it) }
            model().beginLayoutEdit()
            listOf("leftStick","rightStick").forEach { model().selectPanel(it);model().resizePanel(.8f) }
            controls.forEachIndexed { index,c -> model().movePanel(c.id,PanelPosition(index/2f,1f)) }
            model().saveLayoutEdit()
        }
        controls.forEach { compose.onNodeWithTag("control-note-${it.id}").assertIsDisplayed() }
        val image=compose.onNodeWithTag("panel-canvas").captureToImage().asAndroidBitmap()
        val file=File(compose.activity.getExternalFilesDir(null),"custom-controls.png")
        FileOutputStream(file).use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        assertTrue(file.length()>0)
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val exported=automation.executeShellCommand("cp /sdcard/Android/data/io.github.norlive.remotecontrol/files/custom-controls.png /sdcard/Download/custom-controls.png")
        val output=android.os.ParcelFileDescriptor.AutoCloseInputStream(exported).bufferedReader().use { it.readText() }
        assertTrue(output,output.isBlank())
        val size=automation.executeShellCommand("stat -c %s /sdcard/Download/custom-controls.png")
        val bytes=android.os.ParcelFileDescriptor.AutoCloseInputStream(size).bufferedReader().use { it.readText().trim() }
        assertEquals(file.length().toString(),bytes)
    }
    @Test fun addEditReloadAndDeleteWidgetWithNote() {
        compose.onNodeWithTag("panel-settings").performClick()
        compose.onNodeWithTag("panel-controls-settings").performClick()
        compose.onNodeWithTag("control-add").performScrollTo().performClick()
        compose.onNodeWithTag("control-title").performTextReplacement("夹爪")
        compose.onNodeWithTag("control-note").performTextReplacement("按住打开")
        compose.onNodeWithTag("control-channel").performScrollTo().performClick()
        compose.onNodeWithTag("control-channel-3").performClick()
        compose.onNodeWithTag("control-save").performScrollTo().performClick()
        lateinit var id: String
        compose.runOnIdle {
            val control=model().panelControls.last();id=control.id
            assertEquals(3,control.channel);assertEquals("按住打开",control.note)
            assertEquals(control,RemoteViewModel(compose.activity.application).panelControls.last())
        }
        compose.onNodeWithTag("control-edit-$id").performScrollTo().performClick()
        compose.onNodeWithTag("control-title").performTextReplacement("夹爪开关")
        compose.onNodeWithTag("control-save").performScrollTo().performClick()
        compose.onNodeWithTag("panel-controls-done").performScrollTo().performClick()
        compose.onNodeWithTag("settings-done").performScrollTo().performClick()
        compose.onNodeWithTag("control-note-$id").assertTextEquals("按住打开")
        compose.onNodeWithTag("panel-settings").performClick()
        compose.onNodeWithTag("panel-controls-settings").performClick()
        compose.onNodeWithTag("control-delete-$id").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(RemoteViewModel(compose.activity.application).panelControls.any { it.id==id }) }
    }
    @Test fun momentaryLongAndCycleGesturesAndCustomLayoutPersist() {
        val control=PanelControl("test-button","按钮","说明",3,PanelControlType.MOMENTARY,pressValue=77)
        compose.runOnIdle { model().savePanelControl(control) }
        compose.onNodeWithTag("control-test-button").performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(77,model().controls.slider4) }
        compose.onNodeWithTag("control-test-button").performTouchInput { up() }
        compose.runOnIdle { assertEquals(0,model().controls.slider4);model().savePanelControl(control.copy(type=PanelControlType.SHORT_LONG,longValue=88)) }
        compose.onNodeWithTag("control-test-button").performTouchInput { click() }
        compose.runOnIdle { assertEquals(77,model().controls.slider4) }
        compose.waitUntil(2000) { model().controls.slider4==0 }
        compose.onNodeWithTag("control-test-button").performTouchInput { down(center) }
        compose.waitUntil(2000) { model().controls.slider4==88 }
        compose.onNodeWithTag("control-test-button").performTouchInput { up() }
        compose.runOnIdle { assertEquals(0,model().controls.slider4);model().savePanelControl(control.copy(type=PanelControlType.CYCLE,cycleValues=listOf(4,7))) }
        compose.onNodeWithTag("control-test-button").performTouchInput { click() }
        compose.runOnIdle { assertEquals(4,model().controls.slider4) }
        compose.onNodeWithTag("control-test-button").performTouchInput { click() }
        compose.runOnIdle { assertEquals(7,model().controls.slider4) }
        compose.runOnIdle { model().beginLayoutEdit() }
        compose.onNodeWithTag("move-test-button").performTouchInput { swipe(center,center+Offset(-70f,-70f),500) }
        compose.onNodeWithTag("layout-size").performTouchInput { click(Offset(width*.65f,height/2f)) }
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.runOnIdle {
            val reload=RemoteViewModel(compose.activity.application)
            assertEquals(model().layoutPositions[control.id],reload.layoutPositions[control.id])
            assertEquals(model().layoutScales[control.id],reload.layoutScales[control.id])
            assertNotNull(reload.layoutPositions[control.id]);assertNotNull(reload.layoutScales[control.id])
        }
    }
}
