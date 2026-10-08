package com.roflochinsky.noteapp.pipeline

import java.io.IOException
import java.time.LocalDate
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Interrupted requests and partial snapshots must not lose pending edits or hide remote work. */
class RepoStoreReliabilityTest {

    @get:Rule val tmp = TemporaryFolder()

    private val path = "tasks/2026-10-04-first.md"
    private val other = "tasks/2026-10-04-other.md"
    private val text = "---\ntitle: First\npriority: P1\nstatus: open\n---\n\nDescription.\n"

    private fun api() = FakeGithubApi().apply { put(path, text) }

    private fun store(api: GithubApi): RepoStore =
        RepoStore(
                RepoCache(tmp.newFolder(), "owner/test", "test-token"),
                api,
                clock = { LocalDate.parse("2026-10-04") },
            )
            .also { assertEquals(SyncStatus.OK, it.refresh()) }

    @Test
    fun `edit queued and cancelled within refresh never certifies a stale base`() {
        val remote = api()
        remote.put(other, text.replace("First", "Other"))
        var onBlob: (() -> Unit)? = null
        val api =
            object : GithubApi by remote {
                override fun readBlob(sha: String): String {
                    val result = remote.readBlob(sha)
                    onBlob?.also { onBlob = null }?.invoke()
                    return result
                }
            }
        val store = store(api)
        remote.put(path, text.replace("priority: P1", "priority: P3"))
        remote.put(other, text.replace("First", "Changed other"))
        remote.onTree = {
            remote.onTree = null
            val id = store.edit(path, Edit.SetField("priority", "P2"))
            onBlob = { store.cancel(id) }
        }

        assertEquals(SyncStatus.OK, store.refresh())
        assertTrue(store.pendingPaths().isEmpty())
        assertEquals(SyncStatus.OK, store.refresh())
        assertEquals("P3", store.view().tasks.single { it.path == path }.priority)
    }

    @Test
    fun `connection lost while reading conflict keeps the operation retryable`() {
        val remote = api()
        var disconnected = true
        val api =
            object : GithubApi by remote {
                override fun readFile(path: String): RepoCache.Entry {
                    if (disconnected) throw IOException("connection lost during conflict lookup")
                    return remote.readFile(path)
                }
            }
        val store = store(api)
        store.edit(path, Edit.SetField("priority", "P2"))
        remote.put(path, text.replace("Description.", "Updated remotely."))

        assertEquals(RepoStore.Push.RETRY, store.push())
        assertTrue(path in store.pendingPaths())
        disconnected = false
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertTrue(remote.text(path)!!.contains("priority: P2"))
        assertTrue(remote.text(path)!!.contains("Updated remotely."))
    }

    @Test
    fun `malformed successful response keeps the operation retryable`() {
        val api = api()
        val store = store(api)
        store.edit(path, Edit.SetField("priority", "P2"))
        api.fail = JSONException("truncated response")

        assertEquals(RepoStore.Push.RETRY, store.push())
        assertTrue(path in store.pendingPaths())
        api.fail = null
        assertEquals(RepoStore.Push.MORE, store.push())
        assertTrue(api.text(path)!!.contains("priority: P2"))
    }

    @Test
    fun `repository hidden by access failure does not discard the queued edit`() {
        val api = api()
        val store = store(api)
        store.edit(path, Edit.SetField("priority", "P2"))
        api.fail = GithubHttpException(404, "private repository inaccessible")

        assertEquals(RepoStore.Push.RETRY, store.push())
        assertTrue(path in store.pendingPaths())
        assertEquals("P2", store.view().tasks.single().priority)
        assertNull(store.view().notice)
    }

    @Test
    fun `write denied with readable file does not discard the queued edit`() {
        val remote = api()
        val store = store(rejectWrites(remote))
        store.edit(path, Edit.SetField("priority", "P2"))

        assertEquals(RepoStore.Push.RETRY, store.push())
        assertTrue(path in store.pendingPaths())
        assertEquals(text, remote.text(path))
    }

    @Test
    fun `failed deletion confirmation keeps the edit for another attempt`() {
        val remote = api()
        var disconnected = false
        val api =
            object : GithubApi by remote {
                override fun readTree(commitSha: String): Map<String, String> {
                    if (disconnected) throw IOException("connection lost checking tree")
                    return remote.readTree(commitSha)
                }
            }
        val store = store(api)
        store.edit(path, Edit.SetField("priority", "P2"))
        remote.remove(path)
        disconnected = true

        assertEquals(RepoStore.Push.RETRY, store.push())
        assertTrue(path in store.pendingPaths())
        disconnected = false
        assertEquals(RepoStore.Push.MORE, store.push())
        assertFalse(path in store.pendingPaths())
    }

    @Test
    fun `create denied with absent file keeps the intent to create`() {
        val store = store(rejectWrites(api()))
        val created = store.create("Created offline")

        assertEquals(RepoStore.Push.RETRY, store.push())
        assertTrue(created in store.pendingPaths())
        assertTrue(store.view().tasks.any { it.path == created })
    }

    @Test
    fun `lost create acknowledgement reconciles the identical remote file`() {
        val remote = api()
        val store = store(loseFirstAcknowledgement(remote))
        val created = store.create("Created offline")

        assertEquals(RepoStore.Push.RETRY, store.push())
        assertTrue(created in store.pendingPaths())
        assertTrue(created in remote.paths())
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertTrue(store.view().tasks.any { it.path == created })
        assertNull(store.view().notice)
        assertEquals(2, remote.paths().size)
    }

    @Test
    fun `genuine create collision preserves the remote file and reports it`() {
        val api = api()
        val store = store(api)
        val created = store.create("Created offline")
        api.put(created, text)

        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(text, api.text(created))
        assertFalse(created in store.pendingPaths())
        val view = store.view()
        assertTrue(view.notice.orEmpty().contains("уже есть"))
        assertEquals("First", view.tasks.single { it.path == created }.title)
    }

    @Test
    fun `lost subtask acknowledgement does not add the same subtask twice`() {
        val remote = api()
        val store = store(loseFirstAcknowledgement(remote))
        store.edit(path, Edit.AddSubtask("Do it once"))

        assertEquals(RepoStore.Push.RETRY, store.push())
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertEquals(1, remote.text(path)!!.lineSequence().count { it == "- [ ] Do it once" })
        assertEquals(2, remote.writeCalls)
        assertNull(store.view().notice)
    }

    @Test
    fun `lost subtask acknowledgement with a later remote change still creates no duplicate`() {
        val remote = api()
        val store = store(loseFirstAcknowledgement(remote))
        store.edit(path, Edit.AddSubtask("Do it once"))
        assertEquals(RepoStore.Push.RETRY, store.push())
        remote.put(path, remote.text(path)!!.replace("Description.", "Changed remotely."))

        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertEquals(1, remote.text(path)!!.lineSequence().count { it == "- [ ] Do it once" })
        assertTrue(remote.text(path)!!.contains("Changed remotely."))
    }

    @Test
    fun `adding and checking a new subtask offline preserves both operations`() {
        val api = api()
        val store = store(api)
        store.edit(path, Edit.AddSubtask("Do it once"))
        store.edit(path, Edit.ToggleSubtask("Do it once", true))
        assertTrue(store.view().tasks.single().subtasks.single().done)

        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertEquals(1, api.text(path)!!.lineSequence().count { it == "- [x] Do it once" })
    }

    @Test
    fun `single file write does not hide unrelated remote changes`() {
        val api = api()
        val store = store(api)
        api.put(other, text.replace("First", "Remote task"))
        store.edit(path, Edit.SetField("priority", "P2"))

        assertEquals(RepoStore.Push.MORE, store.push())
        val reads = api.readBlobCalls
        assertEquals(SyncStatus.OK, store.refresh())
        assertEquals(setOf(path, other), store.view().tasks.map { it.path }.toSet())
        assertEquals("only the remote file needs downloading", reads + 1, api.readBlobCalls)
        assertEquals("P2", store.view().tasks.single { it.path == path }.priority)
    }

    @Test
    fun `single file delete does not hide unrelated remote changes`() {
        val api = api()
        val store = store(api)
        api.put(other, text.replace("First", "Remote task"))
        store.delete(path)

        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(SyncStatus.OK, store.refresh())
        assertEquals(listOf(other), store.view().tasks.map { it.path })
    }

    @Test
    fun `delete conflict with unchanged text is retried rather than acknowledged`() {
        val remote = api()
        val api =
            object : GithubApi by remote {
                private var conflict = true

                override fun deleteFile(path: String, message: String, sha: String): Written {
                    if (conflict) {
                        conflict = false
                        throw GithubHttpException(409, "concurrent ref update")
                    }
                    return remote.deleteFile(path, message, sha)
                }
            }
        val store = store(api)
        store.delete(path)

        assertEquals(RepoStore.Push.MORE, store.push())
        assertTrue(path in store.pendingPaths())
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertFalse(path in remote.paths())
    }

    @Test
    fun `cancelling pending edit after refresh allows the remote version to load`() {
        val api = api()
        val store = store(api)
        val id = store.edit(path, Edit.SetField("priority", "P2"))
        api.put(path, text.replace("priority: P1", "priority: P3"))
        assertEquals(SyncStatus.OK, store.refresh())
        assertEquals("P2", store.view().tasks.single().priority)

        store.cancel(id)
        assertEquals(SyncStatus.OK, store.refresh())
        assertEquals("P3", store.view().tasks.single().priority)
    }

    @Test
    fun `edit queued during delta download retains its original conflict base`() {
        editDuringDownload(rebuild = false)
    }

    @Test
    fun `edit queued during rebuilt tree download retains its original conflict base`() {
        editDuringDownload(rebuild = true)
    }

    private fun editDuringDownload(rebuild: Boolean) {
        val remote = api()
        var onBlob: (() -> Unit)? = null
        val api =
            object : GithubApi by remote {
                override fun readBlob(sha: String): String {
                    val result = remote.readBlob(sha)
                    val callback = onBlob
                    onBlob = null
                    callback?.invoke()
                    return result
                }
            }
        val store = store(api)
        remote.compareStale = rebuild
        remote.put(path, text.replace("priority: P1", "priority: P3"))
        onBlob = { store.edit(path, Edit.SetField("priority", "P2")) }

        assertEquals(SyncStatus.OK, store.refresh())
        assertEquals("P2", store.view().tasks.single().priority)
        assertEquals(RepoStore.Push.MORE, store.push())
        assertEquals(RepoStore.Push.EMPTY, store.push())
        assertTrue("remote conflict was silently overwritten", remote.text(path)!!.contains("P3"))
        assertTrue(store.view().notice.orEmpty().contains("Приоритет"))
    }

    private fun loseFirstAcknowledgement(remote: FakeGithubApi): GithubApi =
        object : GithubApi by remote {
            private var lose = true

            override fun putFile(
                path: String,
                content: String,
                message: String,
                sha: String?,
            ): Written {
                val written = remote.putFile(path, content, message, sha)
                if (lose) {
                    lose = false
                    throw IOException("server committed, response lost")
                }
                return written
            }
        }

    private fun rejectWrites(remote: FakeGithubApi): GithubApi =
        object : GithubApi by remote {
            override fun putFile(
                path: String,
                content: String,
                message: String,
                sha: String?,
            ): Written = throw GithubHttpException(404, "write not allowed")
        }
}
