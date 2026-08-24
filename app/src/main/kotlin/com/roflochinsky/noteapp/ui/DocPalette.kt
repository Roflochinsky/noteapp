package com.roflochinsky.noteapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Мир «Документ» (комп nikitatrubaev-pdj.4): холодная бумага, найви, синий; коралл только REC. */
object DocPalette {
    val Paper = Color(0xFFFBFBF9)
    val Paper2 = Color(0xFFF3F3EE)
    val Ink = Color(0xFF1F2A44)
    val Mut = Color(0xFF6A7386)
    val Line = Color(0xFFE3E3DC)
    val Blue = Color(0xFF3A6FB8)
    val Rec = Color(0xFFE8553F)
    val RecText = Color(0xFFC8402C)
    val Amber = Color(0xFF8A5A0F)
    val Green = Color(0xFF2E7D53)
    val Nav = Color(0xFF243B63)
}

private val scheme =
    lightColorScheme(
        primary = DocPalette.Blue,
        onPrimary = DocPalette.Paper,
        secondary = DocPalette.Nav,
        background = DocPalette.Paper,
        surface = DocPalette.Paper,
        onBackground = DocPalette.Ink,
        onSurface = DocPalette.Ink,
        outline = DocPalette.Line,
        error = DocPalette.Rec,
    )

// Roboto — системный шрифт Android (пин владельца); mono — для цифр.
private val docType =
    Typography(
        headlineSmall =
            TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                letterSpacing = (-0.4).sp,
                color = DocPalette.Ink,
            ),
        titleMedium =
            TextStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp, color = DocPalette.Ink),
        titleSmall =
            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = DocPalette.Ink),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = DocPalette.Ink),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = DocPalette.Mut),
        labelMedium =
            TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = DocPalette.Mut),
        labelSmall =
            TextStyle(
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Medium,
                color = DocPalette.Mut,
            ),
    )

@Composable
fun DocTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = docType, content = content)
}
