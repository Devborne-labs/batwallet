package com.example.walletlookup

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal

/**
 * The main pieces of the screens, drawn without a phone (Robolectric) on every build. Each test checks what it shows
 * and saves a picture to app/build/outputs/roborazzi, which the Build workflow keeps with the run, so a layout change
 * can be looked at before it's released.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: the app's own one starts background work that a drawing test doesn't need.
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi-night")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        MaterialTheme(colorScheme = DarkBat, typography = BatType) {
            Surface { Box(Modifier.padding(16.dp)) { content() } }
        }
    }

    private fun picture(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    /** Whether a text didn't fit in its space (cut off or shortened with "…"). */
    private fun SemanticsNodeInteraction.cutOff(): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().hasVisualOverflow
    }

    @Test fun totalCardWhileUpdating() {
        show { TotalHeader(emptyList(), emptyMap(), setOf("syncing"), null) {} }
        compose.onNodeWithText("Total").assertIsDisplayed()
        // The light along the card's edge is the only sign, and screen readers hear it.
        compose.onNodeWithContentDescription("Updating…").assertExists()
        picture("total-card-updating")
    }

    @Test fun totalCardAtRest() {
        show { TotalHeader(emptyList(), emptyMap(), emptySet(), null) {} }
        compose.onNodeWithContentDescription("Updating…").assertDoesNotExist()
        picture("total-card")
    }

    @Test fun screenReadersHearUpdatingInTheChosenLanguage() {
        I18n.load(RuntimeEnvironment.getApplication())
        I18n.lang = "ru"
        try {
            show { TotalHeader(emptyList(), emptyMap(), setOf("syncing"), null) {} }
            compose.onNodeWithContentDescription("Обновление…").assertExists()
        } finally {
            I18n.lang = "en"
        }
    }

    @Test fun recentTransactionKeepsItsTime() {
        val time = System.currentTimeMillis() - 3_600_000
        val wallet = "A very long wallet group name · Broke satoshi"
        val sent = Transfer(true, BigDecimal("100"), "0xeb2d5f7c0000000000000000000000000000a0bb", time, null, hash = "0x1")
        show {
            Box(Modifier.width(340.dp)) {
                RecentTxSection(listOf(Triple("w1", "BSC", sent)), names = emptyMap(), walletName = { wallet }, onOpen = { _, _ -> })
            }
        }
        // A long wallet name gives way ("…"); the time always shows in full.
        assertTrue(compose.onNodeWithText(wallet).cutOff())
        val date = compose.onNodeWithText(shortDate(time / 1000), substring = true)
        date.assertIsDisplayed()
        assertFalse(date.cutOff())
        picture("recent-transactions")
    }

    @Test fun loadingDots() {
        show { BatSpinner(size = 30.dp) }
        picture("loading-dots")
    }
}
