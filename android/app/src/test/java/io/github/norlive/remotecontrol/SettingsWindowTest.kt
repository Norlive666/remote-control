package io.github.norlive.remotecontrol

import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = RemoteApplication::class, qualifiers = "w900dp-h400dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsWindowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun cleanup() {
        compose.runOnIdle {
            val app = compose.activity.application as RemoteApplication
            app.controller.updateSettingsOpen(false)
            app.viewModelStore.clear()
        }
    }

    @Test fun settingsAndEditorStayInsideMeasuredActivityWhenConfigurationOverstatesDisplaySize() {
        val resources = compose.activity.resources
        val original = Configuration(resources.configuration)
        val host = compose.activity.window.decorView
        compose.waitForIdle()
        val width = host.width
        val height = host.height
        assertTrue("Activity must be laid out", width > 0 && height > 0)
        try {
            compose.runOnIdle {
                // Reproduce a display configuration larger than the measured app window.
                val oversized = Configuration(original).apply {
                    screenWidthDp += 300
                    screenHeightDp += 150
                }
                @Suppress("DEPRECATION")
                resources.updateConfiguration(oversized, resources.displayMetrics)
            }
            compose.onNodeWithTag("panel-settings").performClick()
            val panel = compose.onNodeWithTag("controller-settings").fetchSemanticsNode().boundsInRoot
            assertTrue("Settings $panel must fit measured window ${width}x$height",
                panel.right <= width + 1f && panel.bottom <= height + 1f)
            compose.onNodeWithTag("settings-done").assertIsDisplayed()
            compose.onNodeWithTag("sticks-apply").assertIsDisplayed()
            compose.onNodeWithTag("panel-controls-settings").performScrollTo().performClick()
            compose.onNodeWithTag("control-add").performClick()
            val save = compose.onNodeWithTag("control-save").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Editor action $save must fit measured window ${width}x$height",
                save.right <= width + 1f && save.bottom <= height + 1f)
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithTag("controller-settings").assertIsDisplayed()
        } finally {
            compose.runOnIdle {
                @Suppress("DEPRECATION")
                resources.updateConfiguration(original, resources.displayMetrics)
            }
        }
    }
}
