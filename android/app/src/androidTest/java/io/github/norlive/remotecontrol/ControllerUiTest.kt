package io.github.norlive.remotecontrol

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControllerUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun model()=ViewModelProvider(compose.activity)[RemoteViewModel::class.java]
    @Before fun resetSavedLayout() {
        compose.runOnIdle { model().apply { beginLayoutEdit();defaultLayoutDraft();saveLayoutEdit();setStickConfig(0,JoystickConfig());setStickConfig(1,JoystickConfig()) } }
    }
    private fun editLayout() {
        compose.onNodeWithTag("panel-settings").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("编辑布局").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("编辑布局").performClick()
    }
    @Test fun savedPositionsReloadAndDefaultsCanBeRestored() {
        editLayout()
        compose.runOnIdle { model().movePanel("leftStick",PanelPosition(.31f,.67f)) }
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.runOnIdle {
            val reloaded=RemoteViewModel(compose.activity.application)
            assertEquals(PanelPosition(.31f,.67f),reloaded.layoutPositions["leftStick"])
            assertFalse(model().controls.enabled)
        }
        compose.activityRule.scenario.recreate()
        val recreated=compose.onNodeWithTag("move-leftStick").fetchSemanticsNode().boundsInRoot
        val canvas=compose.onNodeWithTag("panel-canvas").fetchSemanticsNode().boundsInRoot
        // Window insets can change during recreation; saved positions are relative to the canvas.
        assertEquals(.31f,(recreated.left-canvas.left)/(canvas.width-recreated.width),.01f)
        assertEquals(.67f,(recreated.top-canvas.top)/(canvas.height-recreated.height),.01f)
        editLayout()
        compose.onNodeWithText("恢复默认",useUnmergedTree=true).performClick()
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.runOnIdle { assertTrue(RemoteViewModel(compose.activity.application).layoutPositions.isEmpty()) }
    }
    @Test fun referenceLayoutHasTopSlidersAndCentralBluetoothHub() {
        val left=compose.onNodeWithTag("move-leftStick").fetchSemanticsNode().boundsInRoot
        val right=compose.onNodeWithTag("move-rightStick").fetchSemanticsNode().boundsInRoot
        val hub=compose.onNodeWithTag("move-enable").fetchSemanticsNode().boundsInRoot
        val slider=compose.onNodeWithTag("move-leftSlider").fetchSemanticsNode().boundsInRoot
        assertTrue(slider.bottom<=left.top+2f)
        assertTrue(left.right<hub.left && hub.right<right.left)
        compose.onNodeWithTag("panel-settings").assertIsDisplayed()
    }
    @Test fun redundantButtonsAreRemovedAndModesCycleIndependently() {
        compose.onNodeWithText("连接",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("复位",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("急停",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithTag("stick-mode-0").performClick()
        compose.runOnIdle {
            model().axes(true,1000,800);model().axes(false,1000,800)
            assertEquals(1000,model().controls.lx);assertEquals(0,model().controls.ly)
            assertTrue(model().controls.ry>0)
        }
        compose.onNodeWithTag("stick-mode-0").performClick()
        compose.runOnIdle {
            assertEquals(0,model().controls.lx);assertEquals(0,model().controls.ly)
            model().axes(true,800,-1000)
            assertEquals(0,model().controls.lx);assertEquals(-1000,model().controls.ly)
        }
        compose.onNodeWithTag("stick-mode-0").performClick()
        compose.runOnIdle { assertEquals(JoystickMode.FREE,model().stickConfigs[0].mode) }
    }
    @Test fun stickSettingsApplyAndSurviveReload() {
        compose.onNodeWithTag("panel-settings").performClick()
        compose.onNodeWithTag("stick-range-0").performScrollTo().performTextReplacement("255")
        compose.onNodeWithTag("stick-apply-0").performScrollTo().performClick()
        compose.onNodeWithTag("stick-range-1").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("stick-apply-1").assertIsNotEnabled()
        compose.onNodeWithText("完成",useUnmergedTree=true).performScrollTo().performClick()
        compose.runOnIdle {
            model().setStickConfig(0,model().stickConfigs[0].copy(deadZonePercent=10))
            model().axes(true,1000,80)
            assertEquals(255,model().controls.lx);assertEquals(0,model().controls.ly)
            val reloaded=RemoteViewModel(compose.activity.application)
            assertEquals(JoystickConfig(deadZonePercent=10,maxValue=255),reloaded.stickConfigs[0])
            assertEquals(JoystickConfig(),reloaded.stickConfigs[1])
        }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { assertEquals(255,model().stickConfigs[0].maxValue) }
    }
    @Test fun enableControlDragClampsToCanvasEdges() {
        editLayout()
        val canvas=compose.onNodeWithTag("panel-canvas").fetchSemanticsNode().boundsInRoot
        val before=compose.onNodeWithTag("move-enable").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { swipe(before.center,Offset(canvas.right-1f,canvas.bottom-1f)) }
        val after=compose.onNodeWithTag("move-enable").fetchSemanticsNode().boundsInRoot
        assertTrue(after.left>before.left+30f)
        assertEquals(canvas.right,after.right,2f)
        assertEquals(canvas.bottom,after.bottom,2f)
        compose.onNodeWithText("取消",useUnmergedTree=true).performClick()
    }
    @Test fun slidersMoveWithoutChangingControlValues() {
        compose.runOnIdle { model().axes(true,300,400);model().slider(0,420) }
        editLayout()
        for(id in listOf("leftSlider","rightSlider")) {
            val before=compose.onNodeWithTag("move-$id").fetchSemanticsNode().boundsInRoot
            val delta=Offset(0f,60f)
            compose.onNodeWithTag("move-$id").performTouchInput { swipe(center,center+delta) }
            val after=compose.onNodeWithTag("move-$id").fetchSemanticsNode().boundsInRoot
            assertTrue("$id should move",kotlin.math.abs(after.top-before.top)>15f)
        }
        compose.runOnIdle {
            assertEquals(0,model().controls.lx);assertEquals(0,model().controls.ly)
            assertEquals(420,model().controls.slider1)
            model().axes(true,800,800);model().slider(0,800);model().enable(true)
            assertEquals(0,model().controls.lx);assertEquals(420,model().controls.slider1)
            assertFalse(model().controls.enabled)
        }
        compose.onNodeWithText("取消",useUnmergedTree=true).performClick()
    }
    @Test fun controlsRenderAndStickCancellationReturnsToCenter() {
        compose.onNodeWithTag("左摇杆").assertIsDisplayed()
        compose.onNodeWithTag("右摇杆").assertIsDisplayed()
        compose.onNodeWithTag("滑块 1").assertIsDisplayed()
        compose.onNodeWithTag("滑块 2").assertIsDisplayed()
        val left=compose.onNodeWithTag("左摇杆").fetchSemanticsNode().boundsInRoot
        val right=compose.onNodeWithTag("右摇杆").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(0,left.center);down(1,right.center)
            updatePointerTo(0,Offset(left.left+left.width*.8f,left.top+left.height*.2f))
            updatePointerTo(1,Offset(right.left+right.width*.2f,right.top+right.height*.8f))
            move()
        }
        compose.onNodeWithTag("左摇杆").assertContentDescriptionContains("X 876",substring=true)
        compose.onNodeWithTag("右摇杆").assertContentDescriptionContains("X -876",substring=true)
        compose.onRoot().performTouchInput { up(0) }
        compose.onNodeWithTag("左摇杆").assertContentDescriptionContains("X 0，Y 0",substring=true)
        compose.onNodeWithTag("右摇杆").assertContentDescriptionContains("X -876",substring=true)
        compose.onRoot().performTouchInput { cancel() }
        compose.onNodeWithTag("右摇杆").assertContentDescriptionContains("X 0，Y 0",substring=true)

    }
    @Test fun layoutEditingMovesOnlyDraftAndKeepsAxesNeutral() {
        val initial=compose.onNodeWithTag("左摇杆").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("panel-settings").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("编辑布局").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("编辑布局").performClick()
        compose.onNodeWithTag("move-leftStick").performTouchInput { swipe(center,center+Offset(80f,-35f)) }
        val moved=compose.onNodeWithTag("左摇杆").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(moved.left>initial.left+30f)
        compose.onNodeWithTag("左摇杆").assertContentDescriptionContains("X 0，Y 0",substring=true)
        compose.onNodeWithText("取消",useUnmergedTree=true).performClick()
        val restored=compose.onNodeWithTag("左摇杆").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(initial.left,restored.left,2f)
    }
    @Test fun deadZoneAndSingleAxisModeAreAppliedToTouchGestures() {
        compose.runOnIdle { model().setStickConfig(0,JoystickConfig(deadZonePercent=10,maxValue=500,mode=JoystickMode.HORIZONTAL)) }
        val left=compose.onNodeWithTag("左摇杆").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(0,left.center)
            updatePointerTo(0,Offset(left.left+left.width*.84f,left.top+left.height*.2f));move()
        }
        compose.onNodeWithTag("左摇杆").assertContentDescriptionContains("X 500，Y 0",substring=true)
        compose.onRoot().performTouchInput { up(0) }
        compose.onNodeWithTag("左摇杆").assertContentDescriptionContains("X 0，Y 0",substring=true)
        compose.runOnIdle { model().setStickConfig(0,JoystickConfig(deadZonePercent=10)) }
        compose.onRoot().performTouchInput {
            down(0,Offset(left.center.x+left.width*.02f,left.center.y-left.height*.02f))
        }
        compose.onNodeWithTag("左摇杆").assertContentDescriptionContains("X 0，Y 0",substring=true)
        compose.onRoot().performTouchInput { cancel() }
    }
}
