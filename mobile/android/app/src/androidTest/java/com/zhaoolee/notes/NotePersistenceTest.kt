package com.zhaoolee.notes

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotePersistenceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun typedNoteIsSavedAndSurvivesActivityRecreation() {
        val token = "android_smoke_${System.currentTimeMillis()}"
        compose.onNodeWithContentDescription("新建").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("开始记录，支持 Markdown…").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("开始记录，支持 Markdown…").performTextInput(token)
        compose.onNodeWithText(token, substring = true).assertExists()
        val library = File(compose.activity.filesDir, "Quiet.md")
        compose.waitUntil(10_000) { markdownFiles(library).any { it.readText().contains(token) } }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("编辑便签").fetchSemanticsNodes().isEmpty()
        }
        waitForText(token)

        val matches = markdownFiles(library).filter { it.readText(Charsets.UTF_8).contains(token) }
        assertEquals(1, matches.size)

        compose.activityRule.scenario.recreate()
        waitForText(token)
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun markdownFiles(root: File): List<File> = root.walkTopDown()
        .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
        .toList()
}
