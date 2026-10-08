package com.roflochinsky.noteapp.pipeline

import java.io.IOException
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RawNoteUploadTest {
    private val path = "inbox/2026-10-04-1200.md"
    private val donePath = "встречи/2026-10-04-1200-obhod.md"
    private val raw = raw("20261004-120010")

    @Test
    fun `fresh note creates once`() {
        val api = FakeGithubApi()
        assertEquals(path, RawNoteUpload.upload(api, path, raw))
        assertEquals(raw, api.text(path))
        assertEquals(1, api.writeCalls)
    }

    @Test
    fun `lost acknowledgement reconciles without repeating PUT`() {
        val api = FakeGithubApi()
        val interrupted =
            object : GithubApi by api {
                override fun putFile(
                    path: String,
                    content: String,
                    message: String,
                    sha: String?,
                ): Written {
                    api.putFile(path, content, message, sha)
                    throw IOException("response lost after commit")
                }
            }
        assertThrows(IOException::class.java) { RawNoteUpload.upload(interrupted, path, raw) }
        assertEquals(path, RawNoteUpload.upload(api, path, raw))
        assertEquals(1, api.writeCalls)
    }

    @Test
    fun `lost acknowledgement after Action moved note does not resurrect inbox`() {
        val api = FakeGithubApi()
        api.put(donePath, done(raw))
        assertEquals(donePath, RawNoteUpload.upload(api, path, raw))
        assertEquals(setOf(donePath), api.paths())
        assertEquals(0, api.writeCalls)
    }

    @Test
    fun `another recording in same minute is never mistaken for this one`() {
        val api = FakeGithubApi()
        val other = raw("20261004-120040")
        api.put(path, other)
        assertThrows(IOException::class.java) { RawNoteUpload.upload(api, path, raw) }
        assertEquals(other, api.text(path))
        assertEquals(0, api.writeCalls)
        api.remove(path)
        api.put(donePath, done(other))
        assertEquals(path, RawNoteUpload.upload(api, path, raw))
        assertEquals(raw, api.text(path))
    }

    @Test
    fun `same recording with changed remote transcript blocks instead of duplicating`() {
        val api = FakeGithubApi()
        api.put(donePath, done(raw).replace("осмотр объекта", "исправленный текст"))
        assertThrows(UploadConflictException::class.java) { RawNoteUpload.upload(api, path, raw) }
        assertEquals(0, api.writeCalls)
    }

    @Test
    fun `network failure while checking repository cannot trigger blind create`() {
        val api = FakeGithubApi().apply { fail = IOException("offline") }
        assertThrows(IOException::class.java) { RawNoteUpload.upload(api, path, raw) }
        assertEquals(0, api.writeCalls)
    }

    @Test
    fun `racing create conflict reconciles identical note`() {
        val api = FakeGithubApi()
        api.onWrite = { api.put(path, raw) }
        assertEquals(path, RawNoteUpload.upload(api, path, raw))
        assertEquals(1, api.writeCalls)
    }

    @Test
    fun `timezone change before retry does not duplicate recording`() {
        val api = FakeGithubApi()
        api.put(donePath, done(raw).replace("12:00:10Z", "12:00:10+03:00"))
        assertEquals(donePath, RawNoteUpload.upload(api, path, raw))
        assertEquals(0, api.writeCalls)
    }

    private fun done(md: String) =
        md.replace("status: raw", "status: done")
            .replace("## Транскрипт", "## Саммари\n\nОбсудили объект\n\n## Транскрипт")

    private fun raw(id: String) =
        RawNote.build(
            RawNote.Input(
                id,
                ZoneOffset.UTC,
                20,
                "test device",
                transcriptMd = "[00:00] Спикер 1: осмотр объекта",
            )
        )
}
