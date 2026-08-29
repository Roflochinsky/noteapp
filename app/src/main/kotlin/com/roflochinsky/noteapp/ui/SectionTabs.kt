package com.roflochinsky.noteapp.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.SyncStatus

/**
 * Шапка-переключатель: два заголовка рядом, активный — чернила, неактивный — приглушённый; рядом с
 * активным разделом задач — моно-счётчик открытых.
 *
 * Лупа (решение владельца 2026-08-26 (а)): отдельного экрана поиска нет — тап разворачивает поле
 * ввода прямо в шапке, и найденное сужает текущий список ПОВЕРХ активных чипов. Рисуется она только
 * там, где поиску есть за что зацепиться: [onQuery] не передан — иконки нет (лента получит свой
 * поиск срезом Н5).
 *
 * @param query null — поиск свёрнут; строка (в том числе пустая) — поле развёрнуто.
 */
@Composable
fun SectionTabs(
    active: Tab,
    tasksCount: Int,
    onTab: (Tab) -> Unit,
    onSettings: () -> Unit,
    query: String? = null,
    onQuery: ((String?) -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (query != null && onQuery != null) {
            SearchField(query, onQuery, Modifier.weight(1f))
            IconButton(onClick = { onQuery(null) }) {
                Icon(Icons.Filled.Close, "Закрыть поиск", tint = DocPalette.Mut)
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                TabTitle("Заметки", active == Tab.NOTES) { onTab(Tab.NOTES) }
                Spacer(Modifier.width(18.dp))
                TabTitle("Задачи", active == Tab.TASKS) { onTab(Tab.TASKS) }
                if (active == Tab.TASKS && tasksCount > 0) {
                    Text(
                        tasksCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }
            Row {
                onQuery?.let {
                    IconButton(onClick = { it("") }) {
                        Icon(Icons.Filled.Search, "Поиск", tint = DocPalette.Mut)
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, "Настройки", tint = DocPalette.Mut)
                }
            }
        }
    }
}

/**
 * Строка поиска компа: контур 1px `line`, радиус 12dp, лупа и текст `mut` (DESIGN.md, Inputs).
 * Клавиатура поднимается сама — иначе тап по лупе даёт поле, в которое ещё надо попасть пальцем.
 */
@Composable
private fun SearchField(query: String, onQuery: (String?) -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier =
            modifier
                .border(1.dp, DocPalette.Line, RoundedCornerShape(12.dp))
                .heightIn(min = TOUCH.dp)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = DocPalette.Mut,
            modifier = Modifier.size(18.dp),
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("Поиск по задачам", style = MaterialTheme.typography.bodyMedium)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                cursorBrush = SolidColor(DocPalette.Blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
}

@Composable
private fun TabTitle(title: String, active: Boolean, onClick: () -> Unit) {
    Text(
        title,
        style =
            MaterialTheme.typography.headlineSmall.copy(
                color = if (active) DocPalette.Ink else DocPalette.Mut
            ),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

/**
 * Единственная поверхность для ошибок синка: строка под шапкой, без диалогов и тостов. Живёт на
 * обеих вкладках — первый синк уходит на «Заметках», и молчать об отказе до переключения нельзя.
 */
@Composable
fun SyncLine(sync: SyncStatus, onSettings: () -> Unit) {
    val text =
        when (sync) {
            SyncStatus.OK -> return
            SyncStatus.OFFLINE -> "нет сети — показан кэш"
            SyncStatus.NO_TOKEN -> "нет GitHub-токена — тап, чтобы подключить"
            SyncStatus.NO_ACCESS -> "нет доступа к репо"
            SyncStatus.RATE_LIMIT -> "лимит GitHub исчерпан"
        }
    Box(
        // Строка кликабельна (тап ведёт в настройки) — значит тач-таргет 48dp, вердикт UX.
        modifier =
            Modifier.fillMaxWidth()
                .clickable(enabled = sync == SyncStatus.NO_TOKEN, onClick = onSettings)
                .heightIn(min = TOUCH.dp)
                .padding(horizontal = 22.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = if (sync == SyncStatus.OFFLINE) DocPalette.Mut else DocPalette.Amber
                ),
        )
    }
}
