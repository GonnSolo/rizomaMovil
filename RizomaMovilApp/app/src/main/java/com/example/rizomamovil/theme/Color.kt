package com.example.rizomamovil.theme

import androidx.compose.ui.graphics.Color

val TerminalBackground = Color(0xFF000000) // Pure Black
val RizomaRed = Color(0xFFFF0000)          // Crimson Red (ANSI 196)
val RizomaPink = Color(0xFFFF5FFF)         // Rizoma Pink (ANSI 205)
val RizomaVoidPurple = Color(0xFF7B1FA2)    // Morado Void Rizoma (#560591 / #7B1FA2)
val RizomaCyan = Color(0xFF00E5FF)         // Neon Cyan (ANSI 45)
val RizomaYellow = Color(0xFFFFD700)       // Yellow / Gold (ANSI 226)
val RizomaGreen = Color(0xFF00FF66)        // Terminal Green (ANSI 46)
val TerminalText = Color(0xFFFFFFFF)        // White text
val TerminalBorder = Color(0xFF333333)      // Gray borders
val TerminalBorderLight = Color(0xFF555555) // Lighter gray borders
val TerminalTextMuted = Color(0xFF777777)   // Gray text for hints
val SurfaceDark = Color(0xFF111111)         // Dark surface

data class ProfileColorOption(
    val label: String,
    val code: String,
    val color: Color
)

val AvailableProfileColors = listOf(
    ProfileColorOption("ROJO", "196", RizomaRed),
    ProfileColorOption("ROSA", "205", RizomaPink),
    ProfileColorOption("MORADO", "93", RizomaVoidPurple),
    ProfileColorOption("CYAN", "45", RizomaCyan),
    ProfileColorOption("AMARILLO", "226", RizomaYellow),
    ProfileColorOption("VERDE", "46", RizomaGreen),
    ProfileColorOption("BLANCO", "255", TerminalText)
)

fun parseProfileColor(code: String?): Color {
    return when (code) {
        "196" -> RizomaRed
        "205" -> RizomaPink
        "93", "VOID" -> RizomaVoidPurple
        "45" -> RizomaCyan
        "226" -> RizomaYellow
        "46" -> RizomaGreen
        "255" -> TerminalText
        else -> {
            if (code != null && code.startsWith("#") && (code.length == 7 || code.length == 9)) {
                try {
                    Color(android.graphics.Color.parseColor(code))
                } catch (_: Exception) {
                    RizomaRed
                }
            } else {
                RizomaRed
            }
        }
    }
}
