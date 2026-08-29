package com.roflochinsky.noteapp.pipeline

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Седьмая проверка среза Н2: правки задач не задерживают доставку свежей записи (принцип 2).
 *
 * WorkManager выполняет работу внутри одного уникального имени по очереди, а разные имена идут
 * параллельно. Значит вся проверка сводится к одному: имя цепочки задач не может совпасть с именем
 * цепочки заметки ни при каком `noteId`. Имена — константы продакшна, так что переименование любой
 * из цепочек или попытка свести их в одну этот тест валит. Что параллельные цепочки действительно
 * идут параллельно — гарантия WorkManager, её мы не перепроверяем.
 */
class WorkChainsTest {

    @Test
    fun `цепочка задач не может совпасть с цепочкой заметки`() {
        val ids = listOf("", "2026-08-26-1807-reliz", "repo-write", PipelineQueue.NOTE_PREFIX)
        ids.forEach { id ->
            val note = PipelineQueue.NOTE_PREFIX + id
            assertFalse("цепочки слились на noteId=$id", note == RepoWriteWorker.CHAIN)
        }
        assertFalse(
            "имя цепочки задач стало похоже на цепочку заметки",
            RepoWriteWorker.CHAIN.startsWith(PipelineQueue.NOTE_PREFIX),
        )
    }
}
