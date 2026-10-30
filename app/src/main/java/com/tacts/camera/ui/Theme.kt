package com.tacts.camera.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same family as T.A.R.K.A.S.: near-black ink and ice cyan, with amber marking manual values.
val Ink = Color(0xFF07090B)
val InkRaised = Color(0xFF0E1114)
val InkHigh = Color(0xFF161A1E)
val InkLine = Color(0xFF232930)
val Ice = Color(0xFF7EE3EA)
val IceContainer = Color(0xFF0C2B2F)
val Amber = Color(0xFFF2B84B)
val TextPrimary = Color(0xFFE9EBED)
val TextSecondary = Color(0xFF9AA3AB)
val Record = Color(0xFFFF453A)

@Composable
fun TactsTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = darkColorScheme(
      primary = Ice,
      onPrimary = Ink,
      primaryContainer = IceContainer,
      secondary = Amber,
      background = Ink,
      surface = InkRaised,
      surfaceVariant = InkHigh,
      outline = InkLine,
      onBackground = TextPrimary,
      onSurface = TextPrimary,
      onSurfaceVariant = TextSecondary,
      error = Record
    ),
    content = content
  )
}
