package com.roflochinsky.noteapp.pipeline

import org.json.JSONObject

/**
 * Что изменилось в репо между двумя коммитами — разобранный ответ `GET /compare/{base}...{head}`
 * (research §6.C). Чистый модуль: на входе текст ответа, на выходе действия над картой кэша, сети и
 * Android здесь нет.
 *
 * @param changed путь → свежий blob-SHA. `files[].sha` в ответе — это blob-SHA файла (research
 *   §6.C, проверено живым запросом), поэтому карту кэша можно обновить, ничего не перечитывая, а
 *   тексты дочитать только там, где SHA действительно другой.
 * @param removed пути, которых в новом коммите нет; сюда же попадает `previous_filename`
 *   переименования — старое имя из карты уходит.
 * @param stale ответ разобрать нельзя целиком, картина неполная — звать `trees` и пересобирать
 *   карту с нуля.
 */
data class RepoDelta(
    val changed: Map<String, String>,
    val removed: Set<String>,
    val stale: Boolean,
) {
    companion object {
        /**
         * Потолок `compare`: «includes up to 300 changed files for the entire comparison» (research
         * §6.C). Ровно 300 файлов в ответе — признак усечения: остальные не пришли и отличить «их
         * не было» от «их отрезали» нечем.
         */
        const val FILE_LIMIT = 300

        /** Ветки разошлись — `compare` показывает не «что дописали», а обе стороны развилки. */
        private const val DIVERGED = "diverged"

        /** Единственный статус, при котором путь уходит из карты; остальные — «файл есть». */
        private const val REMOVED = "removed"

        /**
         * Переименование приходит либо `renamed` с `previous_filename`, либо парой `removed` +
         * `added` (вердикт HLD: на это полагаться нельзя). Оба вида здесь дают одно и то же: новый
         * путь в [changed], старый — в [removed]. Текст перекладывается по blob-SHA уже в кэше — он
         * у переименованного файла тот же, дочитывать нечего.
         */
        fun parse(json: String): RepoDelta {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return stale()
            val files = root.optJSONArray("files") ?: return stale()
            if (root.optString("status") == DIVERGED || files.length() >= FILE_LIMIT) return stale()
            val changed = mutableMapOf<String, String>()
            val removed = mutableSetOf<String>()
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val path = file.getString("filename")
                if (file.getString("status") == REMOVED) {
                    removed += path
                } else {
                    changed[path] = file.getString("sha")
                    file
                        .optString("previous_filename")
                        .takeIf { it.isNotEmpty() }
                        ?.let { removed += it }
                }
            }
            return RepoDelta(changed, removed, stale = false)
        }

        private fun stale() = RepoDelta(emptyMap(), emptySet(), stale = true)
    }
}
