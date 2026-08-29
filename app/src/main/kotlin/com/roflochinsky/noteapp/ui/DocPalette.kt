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

/**
 * Минимальная сторона тач-таргета в dp (правило доступности). Растягивать до неё ВИДИМЫЙ элемент не
 * нужно и вредно — комп задаёт чип 31dp, сегмент 38dp, строку подзадачи 37dp: промах в пределах
 * 48dp Compose засчитывает сам (`NodeCoordinator.hitTest` → `hitNear`, радиус
 * `ViewConfiguration.minimumTouchTargetSize` = 48×48dp, дефолт интерфейса,
 * `AndroidViewConfiguration` его не переопределяет). Константа остаётся для мест, где 48dp — это
 * ИМЕННО высота по компу.
 *
 * Проверено байткодом ui 1.7.6 и material3 1.3.1 (`javap -p -c` по распакованным `ui-release.aar` /
 * `material3-release.aar` из `~/.gradle/caches`), чтобы следующий не переспрашивал:
 * - спор двух соседей решается РАССТОЯНИЕМ: `HitTestResult.isHitInMinimumTouchTargetBetter` берёт
 *   новый near-hit, только если он строго ближе (`DistanceAndInLayer.compareTo`: сперва «внутри
 *   слоя», потом `signum(distance)`); порядок отрисовки решает лишь ничью;
 * - НАСТОЯЩЕЕ попадание (`hit`, расстояние `-1f`) всегда перебивает любой near-hit соседа — поэтому
 *   у идущих вплотную кликабельных строк (подзадачи) промаха на соседа не бывает;
 * - near-hit не opt-in: он у любого `PointerInputModifierNode`, `clickable` ничего не включает.
 *   Единственное условие — палец (`PointerType.Touch`); для мыши и стилуса near-hit выключен;
 * - `Modifier.minimumInteractiveComponentSize()` нам не годится: он берёт `max(placeable.height,
 *   48dp)` и отдаёт это в `layout()`, то есть двигает РАЗМЕТКУ и соседей;
 * - клипящий предок (`clip`, `Surface`, `Card`) размером ≥48×48 near-hit наружу себя обнуляет.
 */
internal const val TOUCH = 48

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
    val OnNav = Color(0xFFF4F7FB)

    /** Подложка активного: синий 10% — токен `blue-soft` из DESIGN.md. */
    val BlueSoft = Color(0x1A3A6FB8)

    /** Деструктивное действие — нативный красный Android; коралл остаётся только за записью. */
    val Err = Color(0xFFB3261E)

    /** Обводка деструктивной кнопки: тот же красный 20%. */
    val ErrLine = Color(0x33B3261E)
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
        error = DocPalette.Err,
    )

// Roboto — системный шрифт Android (пин владельца); mono — для цифр.
private val docType =
    Typography(
        // Display (DESIGN.md): переключатель «Заметки | Задачи» и заголовок онбординга.
        // Комп v2 `.switch b` — 1.45rem = 23.2px ≈ 24sp, трекинг −0.02em ≈ −0.4sp.
        headlineSmall =
            TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                letterSpacing = (-0.4).sp,
                color = DocPalette.Ink,
            ),
        // Headline (DESIGN.md): заголовок деталки задачи.
        // Комп v2 `.dt-head h4` — 1.3rem = 20.8px ≈ 21sp, трекинг −0.015em ≈ −0.3sp.
        titleLarge =
            TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp,
                letterSpacing = (-0.3).sp,
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

/**
 * Headline «тот же голос ступенью ниже» (DESIGN.md) — заголовок диалога и шторки.
 *
 * Комп v2 даёт им `.dlg h4` 1.2rem = 19.2px и `.sheet .title` 1.15rem = 18.4px: разница 0.8px, то
 * есть меньше одного sp, и DESIGN.md держит оба одной ступенью — поэтому кегль один, 19sp (он ближе
 * к обоим, чем 18sp). Своего слота между `titleLarge` (21sp) и `titleMedium` (17sp) в Material3
 * нет, поэтому ступень живёт именованным стилем рядом с темой, а не враньём в чужом слоте. Трекинг
 * наследуется от Headline: −0.3sp при 19sp — это ровно −0.015em компа.
 */
internal val OverlayHeadline = docType.titleLarge.copy(fontSize = 19.sp)

@Composable
fun DocTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = docType, content = content)
}
