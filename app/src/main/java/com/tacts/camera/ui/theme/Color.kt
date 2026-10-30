package com.tacts.camera.ui.theme

import androidx.compose.ui.graphics.Color

// T.A.L. base palette, as in T.A.R.K.A.S.: black, white and gray. Every other neutral below is one of
// them laid over another at a given opacity.
val TalBlack = Color(0xFF080808) // Absolute Black
val TalWhite = Color(0xFFF2F2F0) // Precision White
val TalGrayDark = Color(0xFF8C8C8C) // Technical Gray, on dark (6.0:1 on black)

// The accent is the family's: T.A.R.K.A.S. ice. It marks what is selected and every value set by hand.
val TarkasIce = Color(0xFF7EE3EA)

// Precision White over Absolute Black at 5, 7, 9, 11 and 16 %.
val TalInkRaised = Color(0xFF141414)
val TalInkContainer = Color(0xFF181818)
val TalInkHigh = Color(0xFF1D1D1D)
val TalInkContainerHigh = Color(0xFF222222)
val TalInkLine = Color(0xFF2D2D2D)
// Ice over Absolute Black at 16 %: the wash behind a selected item.
val TarkasIceContainer = Color(0xFF1B2B2C)

// The page is pure black, as on the T.A.R.K.A.S. AMOLED theme: the viewfinder is the only light.
val AmoledBlack = Color(0xFF000000)

// The palette has no status colour; coral is the T.A.R.K.A.S. error red, used here for recording.
val TalErrorDark = Color(0xFFFF6B6B)
