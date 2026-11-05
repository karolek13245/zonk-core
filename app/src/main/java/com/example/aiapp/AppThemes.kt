package com.example.aiapp

/**
 * A background theme. The page behind everything is a soft top-to-bottom gradient; the colours are
 * 0xAARRGGBB numbers, one pair for light mode and one pair (plus the colour of cards) for dark mode.
 * In light mode cards stay white. A theme whose top and bottom colours are equal is a flat colour.
 */
class AppTheme(
    val id: String,
    val name: String,
    val lightTop: Long,
    val lightBottom: Long,
    val darkTop: Long,
    val darkBottom: Long,
    val darkCard: Long,
) {
    val isClassic: Boolean get() = id == "classic"
}

/** The first one is the original look of the app and stays the default. */
val THEMES: List<AppTheme> = listOf(
    AppTheme("classic", "Classic", 0xFFF2F2F7, 0xFFF2F2F7, 0xFF1C1C1E, 0xFF1C1C1E, 0xFF2C2C2E),
    AppTheme("midnight", "Midnight", 0xFFEAF0FF, 0xFFDDE6FF, 0xFF0A1028, 0xFF18214D, 0xFF222F66),
    AppTheme("ocean", "Ocean", 0xFFE2F6F8, 0xFFCAEDF2, 0xFF04202B, 0xFF082D3C, 0xFF0D3E51),
    AppTheme("forest", "Forest", 0xFFE7F5EA, 0xFFD0EAD7, 0xFF07190F, 0xFF0D2E20, 0xFF134029),
    AppTheme("sunset", "Sunset", 0xFFFFF0E2, 0xFFFFDDD3, 0xFF2B0F18, 0xFF4A1C25, 0xFF5C2630),
    AppTheme("violet", "Violet", 0xFFF3EDFF, 0xFFE9DFFD, 0xFF150A2B, 0xFF301760, 0xFF3F2480),
)

/** The theme with this id, or the Classic one if the id is unknown (for example after a downgrade). */
fun themeFor(id: String?): AppTheme = THEMES.firstOrNull { it.id == id } ?: THEMES[0]
