package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Applies the Torvian Chatbot theme to the given content.
 *
 * This composable wraps the application in a Material 3 theme with support for
 * both light and dark color schemes. The theme can be forced to dark mode for
 * testing purposes, or it will follow the system setting.
 *
 * @param currentTheme An optional string to force the theme mode ("dark", "light", or null for system default).
 * @param content The composable content to be themed.
 */
@Composable
fun AppTheme(
    currentTheme: String? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = when (currentTheme?.lowercase()) {
        "dark" -> darkColorScheme()
        "light" -> lightColorScheme()
        "deep_cobalt_light" -> lightColorSchemeDeepCobalt()
        "deep_cobalt_dark" -> darkColorSchemeDeepCobalt()
        "modern_neutral_light" -> lightColorSchemeModernNeutral()
        "modern_neutral_dark" -> darkColorSchemeModernNeutral()
        "tech_innovation_light" -> lightColorSchemeTechInnovation()
        "tech_innovation_dark" -> darkColorSchemeTechInnovation()
        else -> if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    }

    PlatformTheme(colorScheme = colorScheme, content = {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    })
}

/**
 * Typography configuration for the Torvian Chatbot application.
 *
 * Uses Material 3 default typography with the system font.
 */
private val Typography = androidx.compose.material3.Typography()

/**
 * Brand color palette
 */
private object BrandColors {
    // --- PRIMARY (Vibrant Blue) ---
    val primaryLight = Color(0xFF2563EB)
    val onPrimaryLight = Color(0xFFFFFFFF)
    val primaryContainerLight = Color(0xFFDBEAFE)
    val onPrimaryContainerLight = Color(0xFF1E3A8A)

    val primaryDark = Color(0xFF60A5FA)
    val onPrimaryDark = Color(0xFF00318C)
    val primaryContainerDark = Color(0xFF1E40AF)
    val onPrimaryContainerDark = Color(0xFFDBEAFE)

    // --- SECONDARY (Teal) ---
    val secondaryLight = Color(0xFF0D9488)
    val onSecondaryLight = Color(0xFFFFFFFF)
    val secondaryContainerLight = Color(0xFFCCFBF1)
    val onSecondaryContainerLight = Color(0xFF134E48)

    val secondaryDark = Color(0xFF2DD4BF)
    val onSecondaryDark = Color(0xFF003732)
    val secondaryContainerDark = Color(0xFF0F766E)
    val onSecondaryContainerDark = Color(0xFFCCFBF1)

    // --- TERTIARY (Purple) ---
    val tertiaryLight = Color(0xFF7C3AED)
    val onTertiaryLight = Color(0xFFFFFFFF)
    val tertiaryContainerLight = Color(0xFFEDE9FE)
    val onTertiaryContainerLight = Color(0xFF3B0764)

    val tertiaryDark = Color(0xFFA78BFA)
    val onTertiaryDark = Color(0xFF3C008D)
    val tertiaryContainerDark = Color(0xFF5B21B6)
    val onTertiaryContainerDark = Color(0xFFEDE9FE)

    // --- ERROR (Red) ---
    val errorLight = Color(0xFFDC2626)
    val onErrorLight = Color(0xFFFFFFFF)
    val errorContainerLight = Color(0xFFFEE2E2)
    val onErrorContainerLight = Color(0xFF7F1D1D)

    val errorDark = Color(0xFFF87171)
    val onErrorDark = Color(0xFF690005)
    val errorContainerDark = Color(0xFF91080E)
    val onErrorContainerDark = Color(0xFFFEE2E2)

    // --- NEUTRALS (Slate/Zinc) ---
    // Light
    val backgroundLight = Color(0xFFF8FAFC)
    val onBackgroundLight = Color(0xFF0F172A)
    val surfaceLight = Color(0xFFFFFFFF)
    val onSurfaceLight = Color(0xFF0F172A)
    val surfaceVariantLight = Color(0xFFE2E8F0)
    val onSurfaceVariantLight = Color(0xFF475569)
    val outlineLight = Color(0xFF94A3B8)
    val outlineVariantLight = Color(0xFFCBD5E1)
    val scrimLight = Color(0xFF000000)

    // Dark
    val backgroundDark = Color(0xFF020617) // Deep navy black
    val onBackgroundDark = Color(0xFFF1F5F9)
    val surfaceDark = Color(0xFF0F172A)
    val onSurfaceDark = Color(0xFFF1F5F9)
    val surfaceVariantDark = Color(0xFF1E293B)
    val onSurfaceVariantDark = Color(0xFF94A3B8)
    val outlineDark = Color(0xFF475569)
    val outlineVariantDark = Color(0xFF334155)
    val scrimDark = Color(0xFF000000)

    // --- FIXED ROLES (Colors that stay consistent) ---
    val primaryFixed = Color(0xFFDBEAFE)
    val onPrimaryFixed = Color(0xFF00174C)
    val secondaryFixed = Color(0xFFCCFBF1)
    val onSecondaryFixed = Color(0xFF00201D)
}

/**
 * Light color scheme, based on the Deep Cobalt palette.
 */
private fun lightColorSchemeDeepCobalt() = lightColorScheme(
    primary = BrandColors.primaryLight,
    onPrimary = BrandColors.onPrimaryLight,
    primaryContainer = BrandColors.primaryContainerLight,
    onPrimaryContainer = BrandColors.onPrimaryContainerLight,
    inversePrimary = BrandColors.primaryDark,
    secondary = BrandColors.secondaryLight,
    onSecondary = BrandColors.onSecondaryLight,
    secondaryContainer = BrandColors.secondaryContainerLight,
    onSecondaryContainer = BrandColors.onSecondaryContainerLight,
    tertiary = BrandColors.tertiaryLight,
    onTertiary = BrandColors.onTertiaryLight,
    tertiaryContainer = BrandColors.tertiaryContainerLight,
    onTertiaryContainer = BrandColors.onTertiaryContainerLight,
    background = BrandColors.backgroundLight,
    onBackground = BrandColors.onBackgroundLight,
    surface = BrandColors.surfaceLight,
    onSurface = BrandColors.onSurfaceLight,
    surfaceVariant = BrandColors.surfaceVariantLight,
    onSurfaceVariant = BrandColors.onSurfaceVariantLight,
    surfaceTint = BrandColors.primaryLight,
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF1F5F9),
    error = BrandColors.errorLight,
    onError = BrandColors.onErrorLight,
    errorContainer = BrandColors.errorContainerLight,
    onErrorContainer = BrandColors.onErrorContainerLight,
    outline = BrandColors.outlineLight,
    outlineVariant = BrandColors.outlineVariantLight,
    scrim = BrandColors.scrimLight,
    // New Surface roles
    surfaceBright = Color(0xFFFCFDFF),
    surfaceDim = Color(0xFFD8E2EF),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFCBD5E1),
    // Fixed roles
    primaryFixed = BrandColors.primaryFixed,
    primaryFixedDim = Color(0xFFBFDBFE),
    onPrimaryFixed = BrandColors.onPrimaryFixed,
    onPrimaryFixedVariant = Color(0xFF2563EB),
    secondaryFixed = BrandColors.secondaryFixed,
    secondaryFixedDim = Color(0xFF99F6E4),
    onSecondaryFixed = BrandColors.onSecondaryFixed,
    onSecondaryFixedVariant = Color(0xFF0D9488),
    tertiaryFixed = Color(0xFFEDE9FE),
    tertiaryFixedDim = Color(0xFFDDD6FE),
    onTertiaryFixed = Color(0xFF1E004B),
    onTertiaryFixedVariant = Color(0xFF7C3AED)
)

/**
 * Dark color scheme, based on the Deep Cobalt palette.
 */
private fun darkColorSchemeDeepCobalt() = darkColorScheme(
    primary = BrandColors.primaryDark,
    onPrimary = BrandColors.onPrimaryDark,
    primaryContainer = BrandColors.primaryContainerDark,
    onPrimaryContainer = BrandColors.onPrimaryContainerDark,
    inversePrimary = BrandColors.primaryLight,
    secondary = BrandColors.secondaryDark,
    onSecondary = BrandColors.onSecondaryDark,
    secondaryContainer = BrandColors.secondaryContainerDark,
    onSecondaryContainer = BrandColors.onSecondaryContainerDark,
    tertiary = BrandColors.tertiaryDark,
    onTertiary = BrandColors.onTertiaryDark,
    tertiaryContainer = BrandColors.tertiaryContainerDark,
    onTertiaryContainer = BrandColors.onTertiaryContainerDark,
    background = BrandColors.backgroundDark,
    onBackground = BrandColors.onBackgroundDark,
    surface = BrandColors.surfaceDark,
    onSurface = BrandColors.onSurfaceDark,
    surfaceVariant = BrandColors.surfaceVariantDark,
    onSurfaceVariant = BrandColors.onSurfaceVariantDark,
    surfaceTint = BrandColors.primaryDark,
    inverseSurface = BrandColors.surfaceLight,
    inverseOnSurface = BrandColors.onSurfaceLight,
    error = BrandColors.errorDark,
    onError = BrandColors.onErrorDark,
    errorContainer = BrandColors.errorContainerDark,
    onErrorContainer = BrandColors.onErrorContainerDark,
    outline = BrandColors.outlineDark,
    outlineVariant = BrandColors.outlineVariantDark,
    scrim = BrandColors.scrimDark,
    // New Surface roles
    surfaceBright = Color(0xFF1E293B),
    surfaceDim = Color(0xFF020617),
    surfaceContainer = Color(0xFF0F172A),
    surfaceContainerLow = Color(0xFF020617),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerHigh = Color(0xFF1E293B),
    surfaceContainerHighest = Color(0xFF334155),
    // Fixed roles (same as light usually, to maintain identity)
    primaryFixed = BrandColors.primaryFixed,
    primaryFixedDim = Color(0xFFBFDBFE),
    onPrimaryFixed = BrandColors.onPrimaryFixed,
    onPrimaryFixedVariant = Color(0xFF2563EB),
    secondaryFixed = BrandColors.secondaryFixed,
    secondaryFixedDim = Color(0xFF99F6E4),
    onSecondaryFixed = BrandColors.onSecondaryFixed,
    onSecondaryFixedVariant = Color(0xFF0D9488),
    tertiaryFixed = Color(0xFFEDE9FE),
    tertiaryFixedDim = Color(0xFFDDD6FE),
    onTertiaryFixed = Color(0xFF1E004B),
    onTertiaryFixedVariant = Color(0xFF7C3AED)
)


/**
 * Modern Neutral Palette: "Stone & Ink"
 * A sophisticated, low-fatigue palette using warm grays and deep indigo.
 */
private object ModernNeutral {
    // --- CORE ACCENTS ---
    // A muted, modern Indigo for "Primary"
    val inkLight = Color(0xFF18181B) // Near black-indigo
    val inkDark = Color(0xFFE4E4E7)

    // A soft, professional Blue-Gray
    val slateLight = Color(0xFF64748B)
    val slateDark = Color(0xFF94A3B8)

    // --- LIGHT THEME (Stone) ---
    val stone50 = Color(0xFFFAFAF9)   // Background
    val stone100 = Color(0xFFF5F5F4)  // Containers
    val stone200 = Color(0xFFE7E5E4)  // Borders
    val stone900 = Color(0xFF1C1917)  // Text

    // --- DARK THEME (Carbon) ---
    val carbon950 = Color(0xFF0C0A09) // Background (Warm black)
    val carbon900 = Color(0xFF1C1917) // Surface
    val carbon800 = Color(0xFF292524) // Containers
    val carbon400 = Color(0xFFA8A29E) // Muted text
}

/**
 * Light color scheme, based on the Modern Neutral palette.
 */
private fun lightColorSchemeModernNeutral() = lightColorScheme(
    primary = Color(0xFF18181B),        // Deep Ink
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E4E7),
    onPrimaryContainer = Color(0xFF18181B),
    inversePrimary = Color(0xFFD4D4D8),

    secondary = Color(0xFF44403C),      // Stone Gray
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5F5F4),
    onSecondaryContainer = Color(0xFF1C1917),

    tertiary = Color(0xFF0F172A),       // Navy Slate
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF1F5F9),
    onTertiaryContainer = Color(0xFF0F172A),

    error = Color(0xFFB91C1C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),

    background = ModernNeutral.stone50,
    onBackground = ModernNeutral.stone900,
    surface = ModernNeutral.stone50,
    onSurface = ModernNeutral.stone900,
    surfaceVariant = ModernNeutral.stone100,
    onSurfaceVariant = ModernNeutral.slateLight,
    outline = ModernNeutral.stone200,
    outlineVariant = Color(0xFFD6D3D1),
    scrim = Color(0xFF000000),

    // Surface Roles
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE7E5E4),
    surfaceContainer = Color(0xFFF5F5F4),
    surfaceContainerLow = Color(0xFFFAFAF9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE7E5E4),
    surfaceContainerHighest = Color(0xFFD6D3D1),

    // Fixed Roles
    primaryFixed = Color(0xFF18181B),
    primaryFixedDim = Color(0xFF3F3F46),
    onPrimaryFixed = Color(0xFFFFFFFF),
    onPrimaryFixedVariant = Color(0xFFD4D4D8),
    secondaryFixed = Color(0xFF44403C),
    secondaryFixedDim = Color(0xFF57534E),
    onSecondaryFixed = Color(0xFFFFFFFF),
    onSecondaryFixedVariant = Color(0xFFD6D3D1),
    tertiaryFixed = Color(0xFF0F172A),
    tertiaryFixedDim = Color(0xFF1E293B),
    onTertiaryFixed = Color(0xFFFFFFFF),
    onTertiaryFixedVariant = Color(0xFF94A3B8)
)

/**
 * Dark color scheme, based on the Modern Neutral palette.
 */
private fun darkColorSchemeModernNeutral() = darkColorScheme(
    primary = Color(0xFFFAFAF9),        // Light Stone as Primary
    onPrimary = Color(0xFF1C1917),
    primaryContainer = Color(0xFF44403C),
    onPrimaryContainer = Color(0xFFFAFAF9),
    inversePrimary = Color(0xFF18181B),

    secondary = Color(0xFFD6D3D1),
    onSecondary = Color(0xFF1C1917),
    secondaryContainer = Color(0xFF292524),
    onSecondaryContainer = Color(0xFFE7E5E4),

    tertiary = Color(0xFF94A3B8),
    onTertiary = Color(0xFF0F172A),
    tertiaryContainer = Color(0xFF1E293B),
    onTertiaryContainer = Color(0xFFCBD5E1),

    error = Color(0xFFEF4444),
    onError = Color(0xFF450a0a),
    errorContainer = Color(0xFF7f1d1d),
    onErrorContainer = Color(0xFFfecaca),

    background = ModernNeutral.carbon950,
    onBackground = Color(0xFFE7E5E4),
    surface = ModernNeutral.carbon950,
    onSurface = Color(0xFFE7E5E4),
    surfaceVariant = ModernNeutral.carbon900,
    onSurfaceVariant = ModernNeutral.slateDark,
    outline = Color(0xFF44403C),
    outlineVariant = Color(0xFF292524),
    scrim = Color(0xFF000000),

    // Surface Roles
    surfaceBright = Color(0xFF292524),
    surfaceDim = ModernNeutral.carbon950,
    surfaceContainer = ModernNeutral.carbon900,
    surfaceContainerLow = ModernNeutral.carbon950,
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerHigh = Color(0xFF292524),
    surfaceContainerHighest = Color(0xFF44403C),

    // Fixed Roles
    primaryFixed = Color(0xFF18181B),
    primaryFixedDim = Color(0xFF3F3F46),
    onPrimaryFixed = Color(0xFFFFFFFF),
    onPrimaryFixedVariant = Color(0xFFD4D4D8),
    secondaryFixed = Color(0xFF44403C),
    secondaryFixedDim = Color(0xFF57534E),
    onSecondaryFixed = Color(0xFFFFFFFF),
    onSecondaryFixedVariant = Color(0xFFD6D3D1),
    tertiaryFixed = Color(0xFF0F172A),
    tertiaryFixedDim = Color(0xFF1E293B),
    onTertiaryFixed = Color(0xFFFFFFFF),
    onTertiaryFixedVariant = Color(0xFF94A3B8)
)


/**
 * Theme: Tech Innovation
 * A high-energy, futuristic palette featuring Electric Blue, Digital Cyan, and Cyber Purple.
 */
private object TechInnovationColors {
    // --- ACCENT PALETTE ---
    val electricBlue = Color(0xFF0061FF)
    val digitalCyan = Color(0xFF00E5FF)
    val cyberPurple = Color(0xFF7000FF)

    // --- LIGHT (Clean Room / Silver) ---
    val lightBg = Color(0xFFF4F7FF)        // Subtle blue-tinted white
    val lightSurface = Color(0xFFFFFFFF)
    val lightBorder = Color(0xFFD0D7E7)

    // --- DARK (Deep Space / Midnight) ---
    val darkBg = Color(0xFF020408)         // Purest deep black-navy
    val darkSurface = Color(0xFF0B101A)    // Elevated surface
    val darkGlowBlue = Color(0xFF3B82F6)   // Primary glow
}

/**
 * Light color scheme, based on the Tech Innovation palette.
 */
private fun lightColorSchemeTechInnovation() = lightColorScheme(
    primary = TechInnovationColors.electricBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001B3D),

    secondary = TechInnovationColors.cyberPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADBFF),
    onSecondaryContainer = Color(0xFF24005A),

    tertiary = TechInnovationColors.digitalCyan,
    onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFFBCF0FF),
    onTertiaryContainer = Color(0xFF001F24),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = TechInnovationColors.lightBg,
    onBackground = Color(0xFF001B3D),
    surface = TechInnovationColors.lightSurface,
    onSurface = Color(0xFF001B3D),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF43474E),
    outline = TechInnovationColors.lightBorder,
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color.Black,

    // Surface Roles for a "layered" UI
    surfaceBright = Color(0xFFF8FAFF),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceContainer = Color(0xFFEDF1F9),
    surfaceContainerLow = Color(0xFFF1F4FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE7EBF4),
    surfaceContainerHighest = Color(0xFFE2E6EF),

    // Fixed Roles
    primaryFixed = Color(0xFFD7E3FF),
    primaryFixedDim = Color(0xFFACC7FF),
    onPrimaryFixed = Color(0xFF001B3D),
    onPrimaryFixedVariant = Color(0xFF0046AC),
    secondaryFixed = Color(0xFFEADBFF),
    secondaryFixedDim = Color(0xFFD1BCFF),
    onSecondaryFixed = Color(0xFF24005A),
    onSecondaryFixedVariant = Color(0xFF5600BB),
    tertiaryFixed = Color(0xFFBCF0FF),
    tertiaryFixedDim = Color(0xFF86D2E9),
    onTertiaryFixed = Color(0xFF001F24),
    onTertiaryFixedVariant = Color(0xFF004E58)
)

/**
 * Dark color scheme, based on the Tech Innovation palette.
 */
private fun darkColorSchemeTechInnovation() = darkColorScheme(
    primary = TechInnovationColors.darkGlowBlue,
    onPrimary = Color(0xFF002F68),
    primaryContainer = Color(0xFF0046AC),
    onPrimaryContainer = Color(0xFFD7E3FF),

    secondary = Color(0xFFD1BCFF), // Lightened Cyber Purple
    onSecondary = Color(0xFF3B0091),
    secondaryContainer = Color(0xFF5600BB),
    onSecondaryContainer = Color(0xFFEADBFF),

    tertiary = Color(0xFF4ED8ED), // Neon Cyan
    onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF004E58),
    onTertiaryContainer = Color(0xFFBCF0FF),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = TechInnovationColors.darkBg,
    onBackground = Color(0xFFD7E3FF),
    surface = TechInnovationColors.darkSurface,
    onSurface = Color(0xFFD7E3FF),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF43474E),
    scrim = Color.Black,

    // Surface Roles for high-tech "glass" feel
    surfaceBright = Color(0xFF131A26),
    surfaceDim = Color(0xFF0B101A),
    surfaceContainer = Color(0xFF111722),
    surfaceContainerLow = Color(0xFF020408),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerHigh = Color(0xFF1B2230),
    surfaceContainerHighest = Color(0xFF262D3D),

    // Fixed Roles (Same as Light to maintain brand)
    primaryFixed = Color(0xFFD7E3FF),
    primaryFixedDim = Color(0xFFACC7FF),
    onPrimaryFixed = Color(0xFF001B3D),
    onPrimaryFixedVariant = Color(0xFF0046AC),
    secondaryFixed = Color(0xFFEADBFF),
    secondaryFixedDim = Color(0xFFD1BCFF),
    onSecondaryFixed = Color(0xFF24005A),
    onSecondaryFixedVariant = Color(0xFF5600BB),
    tertiaryFixed = Color(0xFFBCF0FF),
    tertiaryFixedDim = Color(0xFF86D2E9),
    onTertiaryFixed = Color(0xFF001F24),
    onTertiaryFixedVariant = Color(0xFF004E58)
)
