package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme =
  darkColorScheme(
    primary = ApricotPrimaryDark,
    onPrimary = OnApricotDark,
    primaryContainer = TerracottaContainerDark,
    onPrimaryContainer = OnTerracottaContainerDark,
    secondary = SageSecondaryDark,
    onSecondary = OnSageDark,
    secondaryContainer = SageContainerDark,
    onSecondaryContainer = OnSageContainerDark,
    tertiary = OchreTertiaryDark,
    onTertiary = Color(0xFF3A2700),
    tertiaryContainer = OchreContainerDark,
    onTertiaryContainer = OnOchreContainerDark,
    background = NightInkBackground,
    onBackground = WarmCreamText,
    surface = NightInkSurface,
    onSurface = WarmCreamText,
    surfaceVariant = NightInkVariant,
    onSurfaceVariant = WarmStoneText,
    outline = NightOutline,
    outlineVariant = Color(0xFF332C26),
  )

private val LightColorScheme =
  lightColorScheme(
    primary = TerracottaPrimary,
    onPrimary = Color.White,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = OnTerracottaContainer,
    secondary = ForestSageSecondary,
    onSecondary = Color.White,
    secondaryContainer = ForestSageContainer,
    onSecondaryContainer = OnForestSageContainer,
    tertiary = OchreTertiary,
    onTertiary = Color.White,
    tertiaryContainer = OchreContainer,
    onTertiaryContainer = OnOchreContainer,
    background = CreamBackground,
    onBackground = EspressoInk,
    surface = WarmPaperSurface,
    onSurface = EspressoInk,
    surfaceVariant = ParchmentVariant,
    onSurfaceVariant = UmberMuted,
    surfaceContainerHigh = ParchmentContainerHigh,
    outline = WarmOutline,
    outlineVariant = WarmOutlineSubtle,
    error = ErrorCrimson,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
  )

val AudiolibroShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

@Composable
fun AudiolibroTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    shapes = AudiolibroShapes,
    content = content,
  )
}
