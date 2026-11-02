package com.alo.android

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alo.android.ui.theme.AloAndroidTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual settings flow, with the existing offline fixture and no server/account mutations. */
@RunWith(AndroidJUnit4::class)
class AloSettingsNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityScenario<MainActivity>

    @Before fun openSettings() {
        activity = ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra(AloDesignFixture.EXTRA, "moderation-settings")
        )
        title("Настройки")
    }

    @After fun closeSettings() { activity.close() }

    @Test fun settingsSectionsKeepTheSameBackBoundsAndLabel() {
        val reference = backBounds()
        listOf("Приватность", "Уведомления", "Бизнес-профиль", "Безопасность").forEach { page ->
            compose.onNodeWithText(page).performClick()
            title(page)
            assertSameBounds(reference, backBounds())
            compose.onNodeWithTag("alo.settings.back").assertContentDescriptionEquals("Назад").assertHasClickAction().performClick()
            title("Настройки")
        }
    }

    @Test fun bothBackMethodsReturnOneLevelInsteadOfClosingAllSettings() {
        val reference = backBounds()
        compose.onNodeWithText("Безопасность").performClick()
        compose.onNodeWithText("Сменить пароль").performClick()
        title("Смена пароля")
        assertSameBounds(reference, backBounds())
        Espresso.pressBack()
        title("Безопасность")
        compose.onNodeWithTag("alo.settings.back").performClick()
        title("Настройки")
        compose.onNodeWithText("Приватность").performClick()
        compose.onNodeWithText("Чёрный список").performScrollTo().performClick()
        title("Чёрный список")
        assertSameBounds(reference, backBounds())
        compose.onNodeWithTag("alo.settings.back").performClick()
        title("Приватность")
        Espresso.pressBack()
        title("Настройки")
    }

    @Test fun creatorPanelUsesTheSettingsBackBaseline() {
        val reference = backBounds()
        compose.onNodeWithText("Панель автора").performClick()
        compose.onAllNodesWithTag("alo.settings.title").onLast().assertTextEquals("Панель автора")
        val back = compose.onAllNodesWithTag("alo.settings.back").onLast()
        back.assertContentDescriptionEquals("Назад")
        assertSameBounds(reference, back.fetchSemanticsNode().boundsInRoot)
        back.performClick()
        title("Настройки")
    }

    private fun title(value: String) {
        compose.onNodeWithTag("alo.settings.title").assertTextEquals(value).assertIsDisplayed()
    }

    private fun backBounds() = compose.onNodeWithTag("alo.settings.back").fetchSemanticsNode().boundsInRoot

    private fun assertSameBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1f)
        assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.width, actual.width, 1f)
        assertEquals(expected.height, actual.height, 1f)
    }
}

@RunWith(AndroidJUnit4::class)
class AloSettingsNavigationLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun saveActionDoesNotMoveTheBackButtonOrTitle() {
        val hasSave = mutableStateOf(false)
        var backs = 0
        compose.setContent {
            AloAndroidTheme {
                AloSettingsNavigationBar("Приватность", { backs++ }) {
                    if (hasSave.value) Text("Сохранить", fontSize = 15.sp, modifier = Modifier.width(82.dp).heightIn(min = 48.dp))
                }
            }
        }
        val back = compose.onNodeWithTag("alo.settings.back").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("alo.settings.title").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { hasSave.value = true }
        assertEquals(back, compose.onNodeWithTag("alo.settings.back").fetchSemanticsNode().boundsInRoot)
        assertEquals(title.center.x, compose.onNodeWithTag("alo.settings.title").fetchSemanticsNode().boundsInRoot.center.x, 1f)
        compose.onNodeWithTag("alo.settings.back").performClick()
        assertEquals(1, backs)
    }

    @Test fun largeTextOnNarrowScreensKeepsNavigationAndTitleSeparate() {
        compose.setContent {
            AloAndroidTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Column(Modifier.width(320.dp)) {
                        AloSettingsNavigationBar("Уведомления", {}) {
                            Text("Сохранить", fontSize = 15.sp, modifier = Modifier.testTag("save").heightIn(min = 48.dp))
                        }
                    }
                }
            }
        }
        val title = compose.onNodeWithTag("alo.settings.title").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithTag("alo.settings.back").assertTextEquals("Назад").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("save").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(title.top >= back.bottom)
        assertTrue(title.top >= save.bottom)
        assertTrue(back.right <= save.left)
        assertTrue(title.width > 0)
    }
}
