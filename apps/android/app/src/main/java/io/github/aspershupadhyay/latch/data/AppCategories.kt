// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
package io.github.aspershupadhyay.latch.data

/**
 * Sorts installed apps into the groups the Apps screen filters by. Android
 * gives an app's own declared category (games, social, productivity, …) but
 * has none for money, shopping, or messaging, so those come from the app's
 * name and package first. A declared category Latch has no group for gets
 * its own group under Android's title for it, so new kinds of apps sort
 * themselves. Everything else is "Other".
 *
 * Plain Kotlin so it is tested on the JVM; [declared] is Android's
 * ApplicationInfo.category and [declaredTitle] its localized title.
 */
object AppCategories {
    const val ALL = "All"
    const val OTHER = "Other"

    // ApplicationInfo.CATEGORY_* (API 26+), copied so this file stays plain Kotlin.
    private const val GAME = 0
    private const val AUDIO = 1
    private const val VIDEO = 2
    private const val IMAGE = 3
    private const val SOCIAL = 4
    private const val NEWS = 5
    private const val MAPS = 6
    private const val PRODUCTIVITY = 7
    private const val ACCESSIBILITY = 8

    /** Checked in order: the first group whose words appear in the name or package wins. */
    private val byWords: List<Pair<String, List<String>>> = listOf(
        "Finance & money" to listOf(
            "bank", "pay", "upi", "wallet", "money", "finance", "invest", "stock", "trading", "trade", "credit", "loan",
            "mutual fund", "insurance", "crypto", "paisa", "rupee", "zerodha", "groww", "phonepe", "paytm", "gpay",
            "cred", "bhim", "kite", "coin", "card", "tax", "sbi", "hdfc", "icici", "axis", "kotak", "venmo", "cash",
        ),
        "Shopping" to listOf("shop", "amazon", "flipkart", "myntra", "meesho", "ajio", "nykaa", "mart", "store", "bazaar", "ebay", "etsy", "ikea", "deals"),
        "Food & delivery" to listOf("swiggy", "zomato", "food", "eats", "blinkit", "zepto", "instamart", "dominos", "pizza", "grocer", "dunzo"),
        "Travel & transport" to listOf("uber", "ola", "rapido", "travel", "irctc", "railway", "flight", "airline", "makemytrip", "goibibo", "booking", "airbnb", "redbus", "metro", "cab", "trip"),
        "Messaging & calls" to listOf("whatsapp", "telegram", "messag", "messenger", "sms", "mail", "gmail", "outlook", "signal", "chat", "dialer", "phone", "contacts", "call", "skype", "zoom", "meet", "teams", "duo", "slack", "discord"),
        "Social" to listOf("instagram", "facebook", "snapchat", "twitter", "threads", "linkedin", "reddit", "pinterest", "tiktok", "moj", "sharechat", "tumblr", "quora", "bluesky", "mastodon"),
        "Browsers" to listOf("chrome", "browser", "firefox", "opera", "brave", "edge", "duckduckgo", "samsung internet"),
        "AI & assistants" to listOf("chatgpt", "openai", "claude", "gemini", "copilot", "perplexity", "assistant", "bard"),
        "Video & music" to listOf("youtube", "netflix", "prime video", "hotstar", "jiocinema", "spotify", "music", "gaana", "wynk", "saavn", "podcast", "video", "player", "tv", "mx player", "vlc"),
        "Photos & camera" to listOf("camera", "gallery", "photo", "snapseed", "lightroom", "picsart", "canva", "capcut", "editor"),
        "Productivity" to listOf("docs", "sheets", "slides", "drive", "office", "word", "excel", "notion", "calendar", "keep", "notes", "todo", "task", "figma", "dropbox", "onedrive", "pdf", "scanner", "evernote", "trello", "jira"),
        "Tools & system" to listOf("settings", "calculator", "clock", "files", "file manager", "my files", "weather", "compass", "recorder", "flashlight", "torch", "launcher", "keyboard", "security", "cleaner", "backup", "authenticator", "vpn"),
    )

    /** The group for one app. */
    fun of(packageName: String, label: String, declared: Int?, isGame: Boolean, declaredTitle: (Int) -> String? = { null }): String {
        if (isGame || declared == GAME) return "Games"
        val text = "${label.lowercase()} ${packageName.lowercase()}"
        // Money first: a banking app that calls itself "productivity" is still about money.
        byWords.firstOrNull { (_, words) -> words.any { matches(text, it) } }?.let { return it.first }
        return when (declared) {
            null, -1 -> OTHER
            SOCIAL -> "Social"
            PRODUCTIVITY -> "Productivity"
            AUDIO, VIDEO -> "Video & music"
            IMAGE -> "Photos & camera"
            NEWS -> "News & reading"
            MAPS -> "Maps & navigation"
            ACCESSIBILITY -> "Accessibility"
            // A category Android added later: its own group, under Android's name for it.
            else -> declaredTitle(declared)?.takeIf { it.isNotBlank() }?.take(30) ?: OTHER
        }
    }

    /**
     * Short words ("pay", "ola", "tv") only match whole words or package parts,
     * so "Display" is not a payment app and "Viola" is not a cab.
     */
    private fun matches(text: String, word: String): Boolean {
        if (word.length > 4) return word in text
        var i = text.indexOf(word)
        while (i >= 0) {
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + word.length)
            if ((before == null || !before.isLetter()) && (after == null || !after.isLetter())) return true
            i = text.indexOf(word, i + 1)
        }
        return false
    }

    /** The filter chips: All, then groups by how many apps they hold, Other last. */
    fun chips(categories: Collection<String>): List<Pair<String, Int>> {
        val counts = categories.groupingBy { it }.eachCount()
        val groups = counts.entries.filter { it.key != OTHER }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        return listOf(ALL to categories.size) + groups.map { it.key to it.value } + listOfNotNull(counts[OTHER]?.let { OTHER to it })
    }
}
