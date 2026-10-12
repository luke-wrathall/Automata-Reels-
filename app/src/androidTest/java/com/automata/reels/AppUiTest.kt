package com.automata.reels

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.automata.reels.ca.CaRule
import com.automata.reels.ca.ReelSpec
import com.automata.reels.ca.SeedType
import com.automata.reels.data.FavoritesStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        for (name in listOf("favorites", "settings")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun titleOf(page: String): String {
        val node = compose.onNode(hasTestTag("rule-title") and hasAnyAncestor(hasTestTag(page)))
        return node.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    }

    private fun SemanticsNodeInteraction.stateDescription(): String =
        fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "" }

    @Test
    fun feedLoadsElementaryReelByDefault() {
        waitForTag("reel-0")
        compose.onNodeWithTag("reel-0").assertIsDisplayed()
        val title = titleOf("reel-0")
        assertTrue("title was '$title'", Regex("Rule \\d{1,3}").matches(title))
    }

    @Test
    fun swipingUpShowsTheNextReel() {
        waitForTag("reel-0")
        waitForTag("reel-1")
        compose.onNodeWithTag("feed-pager").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("reel-1").assertIsDisplayed()
        waitForTag("reel-2") // feed keeps generating ahead
        compose.onNodeWithTag("feed-pager").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("reel-2").assertIsDisplayed()
    }

    @Test
    fun likeButtonSavesToFavouritesAndPersists() {
        waitForTag("reel-0")
        val title = titleOf("reel-0")
        val like = compose.onNodeWithTag("reel-0-like")
        assertEquals("not liked", like.stateDescription())
        like.performClick()
        compose.waitForIdle()
        assertEquals("liked", like.stateDescription())
        compose.onNodeWithTag("favorite-count").assertIsDisplayed()

        compose.onNodeWithTag("open-favorites").performClick()
        waitForTag("favorite-0")
        compose.onNodeWithTag("favorite-0").performClick()
        waitForTag("fav-reel-0")
        assertEquals(title, titleOf("fav-reel-0"))
        compose.onNodeWithTag("viewer-back").performClick()
        compose.onNodeWithTag("back").performClick()
        waitForTag("reel-0")

        // Stored on disk, not just in memory.
        compose.waitUntil(5_000) { FavoritesStore(context).load().size == 1 }
        assertEquals(title, FavoritesStore(context).load().single().rule.title)

        // Un-liking removes it again.
        compose.onNodeWithTag("reel-0-like").performClick()
        compose.waitUntil(5_000) { FavoritesStore(context).load().isEmpty() }
    }

    @Test
    fun doubleTapLikes() {
        waitForTag("reel-0")
        compose.onNodeWithTag("reel-0").performTouchInput { doubleClick(center) }
        compose.waitUntil(5_000) { compose.onNodeWithTag("reel-0-like").stateDescription() == "liked" }
    }

    @Test
    fun switchingToVariedModeShowsMultiColourRules() {
        waitForTag("reel-0")
        compose.onNodeWithTag("mode-VARIED").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("rule-title") and hasAnyAncestor(hasTestTag("reel-0")))
                .fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag("reel-0-caption") and hasCaptionContaining("colours"))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("mode-ELEMENTARY").performClick()
        waitForTag("reel-0")
        assertTrue(Regex("Rule \\d{1,3}").matches(titleOf("reel-0")))
    }

    @Test
    fun emptyFavouritesShowsHint() {
        waitForTag("reel-0")
        compose.onNodeWithTag("open-favorites").performClick()
        compose.onNodeWithTag("favorites-empty").assertIsDisplayed()
    }

    @Test
    fun favouritesStoredOnDiskAppearAfterLaunch() {
        scenario.close()
        val spec = ReelSpec(CaRule.elementary(110), SeedType.RANDOM, 77, 151, 2)
        FavoritesStore(context).save(listOf(spec))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForTag("reel-0")
        compose.onNodeWithTag("open-favorites").performClick()
        waitForTag("favorite-0")
        compose.onNodeWithTag("favorite-0").performClick()
        waitForTag("fav-reel-0")
        assertEquals("Rule 110", titleOf("fav-reel-0"))
    }

    private fun hasCaptionContaining(text: String) = SemanticsMatcher("caption contains $text") { node ->
        fun collect(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } + n.children.flatMap { collect(it) }
        collect(node).any { it.contains(text) }
    }
}
