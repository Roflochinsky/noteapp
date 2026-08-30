package com.roflochinsky.noteapp.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор **настоящего** ответа `compare`, снятого с `Roflochinsky/voice-notes-test`
 * (`app/src/test/resources/github/compare-ahead.json`): один изменённый файл и одно переименование
 * с `previous_filename`. Сочинённых json здесь нет.
 */
class RepoDeltaTest {

    private val fixture: String =
        checkNotNull(javaClass.getResource("/github/compare-ahead.json")) { "нет фикстуры" }
            .readText()

    @Test
    fun `изменённый файл приходит со свежим blob-sha`() {
        val delta = RepoDelta.parse(fixture)
        assertEquals(
            "fb52d8f63b29094655138c567093200d4f226a84",
            delta.changed["tasks/2026-08-25-fix-retraev-ocheredi.md"],
        )
        assertFalse(delta.toString(), delta.stale)
    }

    /** `renamed` с `previous_filename`: новое имя появляется, старое уходит из карты. */
    @Test
    fun `переименование в ответе — новый путь со старым blob-sha, старый путь убран`() {
        val delta = RepoDelta.parse(fixture)
        assertEquals(
            "76ad1b7d57476e4869bbbc00ac886a052ca48b5e",
            delta.changed["идеи/2026-08-12-eksport-v-notion-renamed.md"],
        )
        assertEquals(setOf("идеи/2026-08-12-eksport-v-notion.md"), delta.removed)
    }

    /**
     * Края у настоящего ответа снять нечем — репо тестовое и маленькое, а сочинять json нельзя.
     * Поэтому края делаются из той же реальной фикстуры: меняется одно поле или размножается
     * настоящая запись `files`. Формат при этом остаётся тем, что отдаёт GitHub.
     */
    @Test
    fun `разошедшиеся ветки — картина неполная, нужен пересбор`() {
        val diverged = JSONObject(fixture).put("status", "diverged").toString()
        assertTrue(RepoDelta.parse(diverged).stale)
    }

    /** «up to 300 changed files for the entire comparison» (research §6.C) — дальше обрезано. */
    @Test
    fun `триста файлов в ответе — усечение, нужен пересбор`() {
        val root = JSONObject(fixture)
        val files = root.getJSONArray("files")
        val one = files.getJSONObject(0)
        while (files.length() < RepoDelta.FILE_LIMIT) files.put(one)
        assertTrue(RepoDelta.parse(root.toString()).stale)
    }

    /** Не-JSON от CDN или кэптив-портала — не исключение наверх, а тот же пересбор. */
    @Test
    fun `мусор вместо ответа — пересбор, а не падение`() {
        assertTrue(RepoDelta.parse("<html>502 Bad Gateway</html>").stale)
    }

    /**
     * `removed` + `added` вместо `renamed` — второй вид переименования (вердикт HLD). Собирается из
     * настоящих записей фикстуры: у переименования отрывается `previous_filename`, а старый путь
     * приходит отдельной записью `removed`.
     */
    @Test
    fun `переименование парой removed и added разбирается так же`() {
        val root = JSONObject(fixture)
        val files = root.getJSONArray("files")
        val renamed = files.getJSONObject(1)
        val old = renamed.getString("previous_filename")
        renamed.remove("previous_filename")
        renamed.put("status", "added")
        files.put(JSONObject(renamed.toString()).put("filename", old).put("status", "removed"))
        val delta = RepoDelta.parse(root.toString())
        assertEquals(
            "76ad1b7d57476e4869bbbc00ac886a052ca48b5e",
            delta.changed["идеи/2026-08-12-eksport-v-notion-renamed.md"],
        )
        assertEquals(setOf(old), delta.removed)
    }
}
