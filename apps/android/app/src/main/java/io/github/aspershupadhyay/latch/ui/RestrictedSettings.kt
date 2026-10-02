package io.github.aspershupadhyay.latch.ui

/**
 * Android 13+ blocks accessibility services of apps installed from a file
 * ("Restricted setting") until the owner allows it in App info. That block is
 * a platform safety feature against fraud apps; Latch cannot and must not get
 * around it, so it explains the owner's way through, in the words their phone uses.
 */
object RestrictedSettings {
    /** Steps for this phone. [manufacturer] is `Build.MANUFACTURER`. */
    fun steps(manufacturer: String): List<String> {
        val brand = brand(manufacturer)
        val menu = when (brand) {
            Brand.XIAOMI -> "In App info, tap ⋮ (top right) → Allow restricted settings. If there is no ⋮, open Settings → Apps → Manage apps → Latch."
            Brand.OPPO_FAMILY -> "In App info, tap ⋮ (top right) → Allow restricted settings."
            Brand.SAMSUNG -> "In App info, tap ⋮ (top right) → Allow restricted settings."
            Brand.OTHER -> "In App info, tap ⋮ (top right) → Allow restricted settings."
        }
        return listOf(
            "Tap Turn on screen access and try to switch Latch on once. Android shows “Restricted setting”: tap OK. (The allow option only appears after this.)",
            "Tap Open App info below.",
            "$menu Confirm with your PIN or fingerprint.",
            "Come back and switch Latch on again.",
        )
    }

    /** Shown when the steps do not work, e.g. a phone that hides the menu. */
    const val FALLBACK =
        "Still blocked? Install Latch from a computer with “adb install”: Android does not restrict apps installed that way. See the install guide in the Latch README."

    enum class Brand { XIAOMI, OPPO_FAMILY, SAMSUNG, OTHER }

    fun brand(manufacturer: String): Brand = when (manufacturer.trim().lowercase()) {
        "xiaomi", "redmi", "poco" -> Brand.XIAOMI
        "oppo", "realme", "oneplus" -> Brand.OPPO_FAMILY
        "samsung" -> Brand.SAMSUNG
        else -> Brand.OTHER
    }
}
