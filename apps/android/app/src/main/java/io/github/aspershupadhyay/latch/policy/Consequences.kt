package io.github.aspershupadhyay.latch.policy

import io.github.aspershupadhyay.latch.protocol.Observation
import io.github.aspershupadhyay.latch.protocol.UiNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How much human attention an action needs. Mirrors `latch_policy::Consequence`. */
enum class Consequence { NONE, CONSEQUENTIAL, CRITICAL }

/** The shared lists in `packages/schemas/v1/policy/words.json`, bundled as an asset. */
@Serializable
data class PolicyWords(
    @SerialName("consequential_words") val consequentialWords: List<String>,
    @SerialName("consequential_phrases") val consequentialPhrases: List<String>,
    @SerialName("critical_words") val criticalWords: List<String>,
    @SerialName("critical_phrases") val criticalPhrases: List<String>,
    @SerialName("critical_packages") val criticalPackages: List<String>,
    @SerialName("call_packages") val callPackages: List<String>,
    @SerialName("search_field_words") val searchFieldWords: List<String>,
    @SerialName("sensitive_app_words") val sensitiveAppWords: List<String> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): PolicyWords = json.decodeFromString(serializer(), text)
    }
}

/** What the phone itself concludes about a tap or swipe before it runs. */
data class Judgement(val consequence: Consequence, val title: String, val rememberKey: String)

/**
 * The device-side twin of the gateway's tap assessment (`crates/policy`):
 * same scope, same words, same keys. The phone runs it on its own copy of the
 * screen so a gateway that misses a control, or is not the owner's, cannot
 * skip the owner's approval. Screen text only ever raises the outcome.
 */
class Consequences(private val words: PolicyWords) {

    fun classify(scope: List<UiNode>, packageName: String?): Consequence {
        if ((packageName != null && packageName in words.criticalPackages) ||
            scope.any { matches(it, words.criticalWords, words.criticalPhrases) }
        ) {
            return Consequence.CRITICAL
        }
        if ((packageName != null && packageName in words.callPackages) ||
            scope.any { matches(it, words.consequentialWords, words.consequentialPhrases) || showsPhoneNumber(it) }
        ) {
            return Consequence.CONSEQUENTIAL
        }
        return Consequence.NONE
    }

    /** Judges a tap on [node] (null: a point on no element) in [observation]. */
    fun judgeTap(observation: Observation, node: UiNode?, longPress: Boolean, extra: List<UiNode> = emptyList(), double: Boolean = false): Judgement {
        val verb = when {
            longPress -> "Long-press"
            double -> "Double-tap"
            else -> "Tap"
        }
        val pkg = observation.`package`
        val place = pkg?.let { " in $it" } ?: ""
        if (node == null) {
            return Judgement(classify(extra, pkg), "$verb on the screen$place", "${verb.lowercase()}|${pkg ?: "?"}|")
        }
        val scope = scopeOf(observation.nodes, node)
        val label = scope.firstNotNullOfOrNull(::ownLabel)?.let(::shorten) ?: labelOf(node)
        return Judgement(
            classify(scope + extra, pkg),
            "$verb “$label”$place",
            "${verb.lowercase()}|${pkg ?: "?"}|${label.lowercase()}".take(MAX_KEY_CHARS),
        )
    }

    /**
     * Typing then pressing Enter in [field]: sends in a chat, searches in a
     * search box. The field's text is what is being typed, so it never names it.
     */
    fun judgeEnter(observation: Observation, field: UiNode, characters: Int): Judgement {
        val pkg = observation.`package`
        val name = fieldLabel(field)
        var consequence = classify(listOf(field), pkg)
        if (consequence == Consequence.NONE && !isSearchField(field)) consequence = Consequence.CONSEQUENTIAL
        return Judgement(
            consequence,
            "Type $characters characters into “$name” and press Enter${pkg?.let { " in $it" } ?: ""}",
            "enter|${pkg ?: "?"}|${name.lowercase()}".take(MAX_KEY_CHARS),
        )
    }

    /**
     * Apps that may hold money, accounts, or passwords, from their package and
     * name; the same words and matching as `latch_policy::is_sensitive_app`.
     * Only ever adds a warning (ADR-021).
     */
    fun isSensitiveApp(packageName: String, label: String = ""): Boolean =
        words("$packageName $label").any { w -> words.sensitiveAppWords.any { w == it || (it.codePointCount(0, it.length) >= 4 && w.contains(it)) } }

    fun isSearchField(node: UiNode): Boolean = listOfNotNull(node.description, node.resourceId).any { field ->
        val ws = words(field)
        val compact = ws.joinToString("")
        ws.any { it in words.searchFieldWords } || words.searchFieldWords.any { it.length >= 5 && compact.contains(it) }
    }

    fun judgeSwipe(packageName: String?, drag: Boolean = false): Judgement {
        val consequence = if (packageName != null && packageName in words.callPackages) Consequence.CONSEQUENTIAL else Consequence.NONE
        val verb = if (drag) "Drag" else "Swipe"
        return Judgement(consequence, "$verb on the screen${packageName?.let { " in $it" } ?: ""}", "${verb.lowercase()}|${packageName ?: "?"}|")
    }

    private fun words(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun matches(node: UiNode, single: List<String>, phrases: List<String>): Boolean =
        listOfNotNull(node.text, node.description, node.resourceId).any { field ->
            val ws = words(field)
            val joined = " " + ws.joinToString(" ") + " "
            val compact = ws.joinToString("")
            ws.any { it in single } ||
                phrases.any { joined.contains(" " + words(it).joinToString(" ") + " ") } ||
                // resource ids like `btnSignOut` or `sign_out` collapse to one token
                listOf("signout", "logout").any { it in single && compact.contains(it) }
        }

    companion object {
        const val MAX_SCOPE_NODES = 64
        const val MAX_KEY_CHARS = 160

        /** The tapped node, what is drawn inside it, and if none is labeled, the nearest labeled ancestor. */
        fun scopeOf(nodes: List<UiNode>, node: UiNode): List<UiNode> {
            val scope = mutableListOf(node)
            val frontier = ArrayDeque(listOf(node.id))
            while (frontier.isNotEmpty()) {
                val parent = frontier.removeLast()
                for (child in nodes) {
                    if (child.parent != parent) continue
                    if (scope.size >= MAX_SCOPE_NODES) break
                    if (scope.any { it.id == child.id }) continue
                    scope += child
                    frontier.addLast(child.id)
                }
            }
            if (scope.all { ownLabel(it) == null }) {
                var cursor = node.parent
                repeat(3) {
                    val parent = cursor?.let { id -> nodes.firstOrNull { it.id == id } } ?: return scope
                    if (ownLabel(parent) != null) {
                        scope += parent
                        return scope
                    }
                    cursor = parent.parent
                }
            }
            return scope
        }

        /** Smallest node containing the point, preferring actionable ones (`Observation::node_at`). */
        fun nodeAt(nodes: List<UiNode>, x: Int, y: Int): UiNode? =
            nodes.filter { it.bounds.contains(x, y) }
                .minWithOrNull(compareBy<UiNode>({ if (it.clickable || it.editable) 0 else 1 }, { area(it) }))

        private fun area(n: UiNode): Long =
            if (n.bounds.isEmpty) 0 else (n.bounds.right - n.bounds.left).toLong() * (n.bounds.bottom - n.bounds.top)

        fun showsPhoneNumber(node: UiNode): Boolean = listOfNotNull(node.text, node.description).any { raw ->
            val t = raw.trim()
            val digits = t.count { it in '0'..'9' }
            digits in 7..15 && t.all { it in '0'..'9' || it in " +-(). " }
        }

        private fun ownLabel(n: UiNode): String? = n.text?.takeIf { it.isNotBlank() } ?: n.description?.takeIf { it.isNotBlank() }

        fun labelOf(n: UiNode): String = shorten(ownLabel(n) ?: n.resourceId ?: n.role)

        private fun fieldLabel(n: UiNode): String =
            shorten(n.description?.takeIf { it.isNotBlank() } ?: n.resourceId?.substringAfterLast('/') ?: n.role)

        private fun shorten(raw: String): String {
            val single = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            val cps = single.codePointCount(0, single.length)
            if (cps <= 48) return single
            return single.substring(0, single.offsetByCodePoints(0, 48)) + "…"
        }
    }
}
