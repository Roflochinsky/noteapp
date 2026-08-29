package com.roflochinsky.noteapp.pipeline

/**
 * Эталонная пара Action — файлы `app/src/test/resources/action/`, README лежит рядом с ними.
 *
 * Отдельным файлом, а не внутри одного теста: пару читают и [ActionFixtureTest], и
 * [DoneNoteParserTest] (образец общего тестового файла — `FakeGithubApi.kt`). Якорь ресурса — сам
 * хелпер, продакшн-классы для этого не нужны.
 */
internal object ActionFixture {

    /** Текст файла пары по имени — как оно в папке, вместе с `.md`. */
    fun text(name: String): String =
        checkNotNull(javaClass.getResource("/action/$name")) { "нет фикстуры $name" }.readText()
}
