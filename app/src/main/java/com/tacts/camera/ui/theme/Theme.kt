package com.tacts.camera.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// The T.A.R.K.A.S. AMOLED scheme: T.A.L. base (Precision White and Technical Gray on black) with the
// ice as the accent. A camera is always dark, like the T.A.R.K.A.S. player page, so there is no light
// theme: a bright frame around the viewfinder would change how the photo is judged.
private val TactsColorScheme = darkColorScheme(
  primary = TarkasIce,
  onPrimary = TalBlack,
  primaryContainer = TarkasIceContainer,
  onPrimaryContainer = TarkasIce,
  secondary = TalGrayDark,
  onSecondary = TalBlack,
  secondaryContainer = TalInkHigh,
  onSecondaryContainer = TalWhite,
  tertiary = TarkasIce,
  onTertiary = TalBlack,
  tertiaryContainer = TarkasIceContainer,
  onTertiaryContainer = TarkasIce,
  background = AmoledBlack,
  onBackground = TalWhite,
  surface = TalBlack,
  onSurface = TalWhite,
  surfaceVariant = TalInkRaised,
  onSurfaceVariant = TalGrayDark,
  outline = TalInkLine,
  outlineVariant = TalInkHigh,
  surfaceContainer = TalInkRaised,
  surfaceContainerHigh = TalInkHigh,
  error = TalErrorDark,
  onError = TalBlack
)

@Composable
fun TactsTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = TactsColorScheme, typography = TactsTypography) {
    // Every press in the app answers the same way (Highlight), not with the Material ripple.
    CompositionLocalProvider(LocalIndication provides Highlight, content = content)
  }
}
