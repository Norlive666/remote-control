package io.github.norlive.remotecontrol

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ControllerUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
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
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.getExternalFilesDir(null),"controller.png"))
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
