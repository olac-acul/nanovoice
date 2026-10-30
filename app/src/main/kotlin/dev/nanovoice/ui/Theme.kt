package dev.nanovoice.ui

import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nanovoice.ThemeMode

/** The three colours of the app icon gradient, used by the orb and as accents. */
object Brand {
    val Teal = Color(0xFF2BF2D1)
    val Blue = Color(0xFF016FBF)
    val Deep = Color(0xFF0035B8)
}

/** Colours the Material scheme has no slot for. */
class ExtraColors(
    val success: Color,
    val warning: Color,
    val bubbleBorder: Color,
    /** Highlight, body and shade of the orb gradient. */
    val orb: List<Color> = listOf(Brand.Teal, Brand.Blue, Brand.Deep),
)

val LocalExtraColors = staticCompositionLocalOf { ExtraColors(Color.Unspecified, Color.Unspecified, Color.Unspecified) }

val MaterialTheme.extra: ExtraColors @Composable @ReadOnlyComposable get() = LocalExtraColors.current

private val LightColors = lightColorScheme(
    primary = Color(0xFF016FBF),
    onPrimary = Color.White,
    // The person's own bubbles: a quiet blue, so the replies are what the eye reads first.
    primaryContainer = Color(0xFFD7E8FB),
    onPrimaryContainer = Color(0xFF0B1B33),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F7F0),
    onSecondaryContainer = Color(0xFF00322B),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0B1B33),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B1B33),
    surfaceVariant = Color(0xFFE6EEFA),
    onSurfaceVariant = Color(0xFF566883),
    // Every surface level, so no Material component falls back to the stock lilac and grey ones.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9EFF8),
    surfaceContainerHighest = Color(0xFFE1E8F3),
    surfaceDim = Color(0xFFDCE3EE),
    surfaceBright = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF1E2A3D),
    inverseOnSurface = Color(0xFFEEF3FA),
    inversePrimary = Color(0xFF9CCBFF),
    outline = Color(0xFF8C9BB3),
    outlineVariant = Color(0xFFD9E2ED),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4CB3FF),
    onPrimary = Color(0xFF00213A),
    primaryContainer = Color(0xFF1E3868),
    onPrimaryContainer = Color(0xFFE6EEFB),
    secondary = Color(0xFF2BF2D1),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF00423A),
    onSecondaryContainer = Color(0xFFB6FBEF),
    background = Color(0xFF070D1A),
    onBackground = Color(0xFFE6EEFB),
    surface = Color(0xFF0E1830),
    onSurface = Color(0xFFE6EEFB),
    surfaceVariant = Color(0xFF14213F),
    onSurfaceVariant = Color(0xFF8A9BB8),
    surfaceContainerLowest = Color(0xFF070D1A),
    surfaceContainerLow = Color(0xFF0B1529),
    surfaceContainer = Color(0xFF0E1830),
    surfaceContainerHigh = Color(0xFF16233F),
    surfaceContainerHighest = Color(0xFF1C2B4D),
    surfaceDim = Color(0xFF070D1A),
    surfaceBright = Color(0xFF1C2B4D),
    inverseSurface = Color(0xFFE6EEFB),
    inverseOnSurface = Color(0xFF14213F),
    inversePrimary = Color(0xFF016FBF),
    outline = Color(0xFF5D6F8F),
    outlineVariant = Color(0xFF1D2B4A),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0A06),
)

// The strongest weights for the page titles; titles inside a page, labels and actions one step lighter.
private val NanoTypography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

private val NanoShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/** App theme: custom light and dark palettes; [mode] can force one or follow the system. */
@Composable
fun NanoTheme(mode: ThemeMode, dynamicColors: Boolean = false, content: @Composable () -> Unit) {
    val dark = isDarkTheme(mode)
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    // Keep the status and navigation bar icons readable on the chosen theme, not on the system one.
    DisposableEffect(activity, dark) {
        val style = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose {}
    }
    val dynamic = dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        dynamic -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    var extra = if (dark) ExtraColors(Color(0xFF2BF2D1), Color(0xFFFFC857), Color(0xFF1D2B4A))
    else ExtraColors(Color(0xFF00796B), Color(0xFFB26A00), Color(0xFFD9E2ED))
    // With system colours the orb takes the system accent too: its shades are derived from the primary colour.
    if (dynamic) extra = ExtraColors(
        extra.success, extra.warning, extra.bubbleBorder,
        listOf(lerp(scheme.primary, Color.White, 0.5f), scheme.primary, lerp(scheme.primary, Color.Black, 0.5f)),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(
            colorScheme = scheme,
            typography = NanoTypography,
            shapes = NanoShapes,
            content = content,
        )
    }
}
