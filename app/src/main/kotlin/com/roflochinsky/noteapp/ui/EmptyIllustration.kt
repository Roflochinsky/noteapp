package com.roflochinsky.noteapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/** Плоский векторный человек с телефоном — по компу (пустое состояние/онбординг). */
@Composable
fun EmptyIllustration(modifier: Modifier = Modifier) {
    val skin = Color(0xFFB4643C)
    val ink = DocPalette.Ink
    val blue = DocPalette.Blue
    val nav = DocPalette.Nav
    Canvas(modifier = modifier.size(220.dp, 164.dp)) {
        val u = size.width / 220f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        // тень
        drawOval(Color(0xFFE7E7DF), topLeft = p(56f, 148f), size = Size(108f * u, 12f * u))
        // ноги
        drawLine(nav, p(98f, 96f), p(85f, 124f), strokeWidth = 10f * u, cap = StrokeCap.Round)
        drawLine(nav, p(122f, 96f), p(137f, 122f), strokeWidth = 10f * u, cap = StrokeCap.Round)
        // обувь
        rotate(-16f, p(83f, 125f)) {
            drawRoundRect(ink, p(74f, 121f), Size(19f * u, 9f * u), CornerRadius(4.5f * u))
        }
        rotate(13f, p(141f, 123f)) {
            drawRoundRect(ink, p(132f, 119f), Size(19f * u, 9f * u), CornerRadius(4.5f * u))
        }
        // руки
        drawLine(blue, p(92f, 62f), p(66f, 42f), strokeWidth = 9f * u, cap = StrokeCap.Round)
        drawLine(blue, p(128f, 62f), p(152f, 40f), strokeWidth = 9f * u, cap = StrokeCap.Round)
        drawCircle(skin, 5.5f * u, p(63f, 39f))
        drawCircle(skin, 5.5f * u, p(154f, 37f))
        // телефон в руке
        drawRoundRect(ink, p(149f, 15f), Size(15f * u, 26f * u), CornerRadius(4f * u))
        drawRoundRect(DocPalette.Paper, p(152f, 19f), Size(9f * u, 16f * u), CornerRadius(2f * u))
        // торс
        drawRoundRect(blue, p(90f, 52f), Size(40f * u, 44f * u), CornerRadius(18f * u))
        // голова + волосы
        drawCircle(skin, 15f * u, p(110f, 34f))
        drawArc(
            ink,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = p(95f, 19f),
            size = Size(30f * u, 30f * u),
        )
        // конфетти (без коралла — он только у REC)
        drawCircle(Color(0xFFE9A23B), 4f * u, p(52f, 24f))
        drawCircle(blue, 3.5f * u, p(170f, 60f))
        drawCircle(DocPalette.Green, 3f * u, p(40f, 66f))
    }
}
