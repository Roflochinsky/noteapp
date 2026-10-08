package com.roflochinsky.noteapp

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Checks the debug-only identity without changing the production namespace or components. */
@RunWith(RobolectricTestRunner::class)
class BuildIsolationTest {

    private val app = RuntimeEnvironment.getApplication()
    private val packageManager = app.packageManager

    @Test
    fun `debug package and label are isolated`() {
        assertEquals("com.roflochinsky.noteapp.communications", app.packageName)
        assertEquals(
            "MyNoteBook Communications",
            packageManager.getApplicationLabel(app.applicationInfo),
        )
    }

    @Test
    fun `launcher and assistant retain original class names in isolated package`() {
        val launch = packageManager.getLaunchIntentForPackage(app.packageName)
        assertNotNull(launch)
        assertEquals(
            ComponentName(app.packageName, "com.roflochinsky.noteapp.MainActivity"),
            launch!!.component,
        )
        val assistant =
            packageManager.resolveService(
                Intent("android.service.voice.VoiceInteractionService").setPackage(app.packageName),
                0,
            )
        assertNotNull(assistant)
        assertEquals(app.packageName, assistant!!.serviceInfo.packageName)
        assertEquals("com.roflochinsky.noteapp.assist.AssistService", assistant.serviceInfo.name)
    }

    @Test
    fun `provider authorities use isolated application id`() {
        val providers =
            packageManager.getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS).providers
        assertNotNull(providers)
        assertTrue(providers!!.isNotEmpty())
        providers.forEach { provider ->
            provider.authority.split(';').forEach { authority ->
                assertTrue(authority, authority.startsWith("${app.packageName}."))
            }
        }
    }
}
