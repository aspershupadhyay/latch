package io.github.aspershupadhyay.latch.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCategoriesTest {
    private fun of(pkg: String, label: String, declared: Int? = -1, game: Boolean = false, title: String? = null) =
        AppCategories.of(pkg, label, declared, game) { title }

    @Test
    fun moneyAppsAreFinanceEvenWhenTheyDeclareSomethingElse() {
        assertEquals("Finance & money", of("com.phonepe.app", "PhonePe", declared = 7))
        assertEquals("Finance & money", of("com.sbi.lotusintouch", "YONO SBI"))
        assertEquals("Finance & money", of("net.one97.paytm", "Paytm"))
        assertEquals("Finance & money", of("com.google.android.apps.nbu.paisa.user", "Google Pay"))
    }

    @Test
    fun shortWordsMatchOnlyWholeWords() {
        // "Display" holds "pay", "Viola" holds "ola": neither is money or a cab.
        assertEquals("Other", of("com.example.display", "Display Tuner"))
        assertEquals("Other", of("com.example.viola", "Viola"))
        assertEquals("Travel & transport", of("com.olacabs.customer", "Ola"))
    }

    @Test
    fun androidsDeclaredCategoriesAreUsedAndNewOnesGetTheirOwnGroup() {
        assertEquals("Games", of("com.supercell.clashofclans", "Clash of Clans", game = true))
        assertEquals("Games", of("com.example.puzzle", "Puzzle", declared = 0))
        assertEquals("News & reading", of("com.example.daily", "The Daily", declared = 5))
        assertEquals("Maps & navigation", of("com.example.go", "Go", declared = 6))
        assertEquals("Health & fitness", of("com.example.steps", "Steps", declared = 42, title = "Health & fitness"))
        assertEquals("Other", of("com.example.thing", "Thing", declared = 42, title = null))
    }

    @Test
    fun wellKnownAppsLandWhereOwnersLookForThem() {
        assertEquals("Messaging & calls", of("com.whatsapp", "WhatsApp"))
        assertEquals("Social", of("com.instagram.android", "Instagram"))
        assertEquals("Productivity", of("com.figma.mirror", "Figma"))
        assertEquals("AI & assistants", of("com.openai.chatgpt", "ChatGPT"))
        assertEquals("Shopping", of("in.amazon.mShop.android.shopping", "Amazon Shopping"))
        assertEquals("Browsers", of("com.android.chrome", "Chrome"))
    }

    @Test
    fun chipsStartWithAllAndEndWithOther() {
        val chips = AppCategories.chips(listOf("Social", "Other", "Games", "Social", "Other", "Finance & money"))
        assertEquals(listOf("All" to 6, "Social" to 2, "Finance & money" to 1, "Games" to 1, "Other" to 2), chips)
    }
}
