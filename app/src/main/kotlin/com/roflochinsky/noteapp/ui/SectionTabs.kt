package com.roflochinsky.noteapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roflochinsky.noteapp.pipeline.SyncStatus

/**
 * Шапка-переключатель: два заголовка рядом, активный — чернила, неактивный — приглушённый; рядом с
 * активным разделом задач — моно-счётчик открытых. Лупа приходит в Н3 вместе с чипами (решение
 * плана 2026-08-26(а)): поиск нужен, но раньше фильтров ему не за что зацепиться.
 */
@Composable
fun SectionTabs(active: Tab, tasksCount: Int, onTab: (Tab) -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, "Настройки", tint = DocPalette.Mut)
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
