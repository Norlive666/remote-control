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
        compose.runOnIdle { model().apply { beginLayoutEdit();defaultLayoutDraft();saveLayoutEdit() } }
    }
    private fun editLayout() {
        compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
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
        val saved=compose.onNodeWithTag("move-leftStick").fetchSemanticsNode().boundsInRoot
        compose.activityRule.scenario.recreate()
        val recreated=compose.onNodeWithTag("move-leftStick").fetchSemanticsNode().boundsInRoot
        assertEquals(saved.left,recreated.left,2f)
        assertEquals(saved.top,recreated.top,2f)
        editLayout()
        compose.onNodeWithText("恢复默认",useUnmergedTree=true).performClick()
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.runOnIdle { assertTrue(RemoteViewModel(compose.activity.application).layoutPositions.isEmpty()) }
    }
    @Test fun sixCharacterFunctionKeyLabelFitsWithoutClipping() {
        compose.runOnIdle { model().setKey(0,"开启电机使能","connect") }
        val label=compose.onNodeWithText("开启电机使能",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val pixelsPerSp=compose.activity.resources.displayMetrics.scaledDensity
        assertTrue("All six characters should fit on one line",label.width>=66f*pixelsPerSp-2f)
        compose.runOnIdle { model().setKey(0,"连接","connect") }
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
    @Test fun slidersAndKeysMoveWithoutChangingControlValues() {
        compose.runOnIdle { model().axes(true,300,400);model().slider(0,420) }
        editLayout()
        for(id in listOf("leftSlider","key0")) {
            val before=compose.onNodeWithTag("move-$id").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("move-$id").performTouchInput { swipe(center,center+Offset(0f,60f)) }
            val after=compose.onNodeWithTag("move-$id").fetchSemanticsNode().boundsInRoot
            assertTrue("$id should move",after.top>before.top+15f)
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
        compose.onNodeWithTag("左摇杆").assertContentDescriptionContains("X 882",substring=true)
        compose.onNodeWithTag("右摇杆").assertContentDescriptionContains("X -882",substring=true)
        compose.onRoot().performTouchInput { up(0) }
        compose.onNodeWithTag("左摇杆").assertContentDescriptionContains("X 0，Y 0",substring=true)
        compose.onNodeWithTag("右摇杆").assertContentDescriptionContains("X -882",substring=true)
        compose.onRoot().performTouchInput { cancel() }
        compose.onNodeWithTag("右摇杆").assertContentDescriptionContains("X 0，Y 0",substring=true)

    }
    @Test fun layoutEditingMovesOnlyDraftAndKeepsAxesNeutral() {
        val initial=compose.onNodeWithTag("左摇杆").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
        compose.onNodeWithText("编辑布局").performClick()
        compose.onNodeWithTag("move-leftStick").performTouchInput { swipe(center,center+Offset(80f,-35f)) }
        val moved=compose.onNodeWithTag("左摇杆").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(moved.left>initial.left+30f)
        compose.onNodeWithTag("左摇杆").assertContentDescriptionContains("X 0，Y 0",substring=true)
        compose.onNodeWithText("取消",useUnmergedTree=true).performClick()
        val restored=compose.onNodeWithTag("左摇杆").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(initial.left,restored.left,2f)
    }
    @Test fun stopRemainsLatchedAcrossResetAndSettingsOpen() {
        compose.onNodeWithText("急停",useUnmergedTree=true).performClick()
        compose.onNodeWithText("急停锁定").assertIsDisplayed()
        compose.onNodeWithText("复位",useUnmergedTree=true).performClick()
        compose.onNodeWithText("急停锁定").assertIsDisplayed()
        compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
        compose.onNodeWithText("面板设置").assertIsDisplayed()
    }
}
