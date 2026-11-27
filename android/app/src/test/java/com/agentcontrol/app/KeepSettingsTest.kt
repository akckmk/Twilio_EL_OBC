package com.agentcontrol.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards "update without re-entering settings". Saved settings survive an update only if
 * the new version looks for them under the same names and the app keeps the same
 * signing key. If this fails, a change would wipe saved settings.
 */
class KeepSettingsTest {
    private fun source(path: String) = File(path).readText()

    @Test fun storageNamesNeverChange() {
        val s = source("src/main/java/com/agentcontrol/app/SecureStore.kt")
        assertTrue(s.contains("KEY_ALIAS = \"agent_control_settings_key_v2\""))
        assertTrue(s.contains("PREFS = \"secure_settings\""))
        assertTrue(s.contains("FIELD = \"blob_v2\""))
        assertTrue(s.contains(".put(\"baseUrl\"") && s.contains(".put(\"accessKey\""))
    }

    @Test fun appNeverStoresProviderKeys() {
        for (f in File("src/main/java/com/agentcontrol/app").listFiles()!!) {
            val s = f.readText()
            assertFalse(f.name, s.contains("xi-api-key") || s.contains("api.twilio.com") || s.contains("api.elevenlabs.io"))
        }
    }

    @Test fun appIdAndSigningKeyNeverChange() {
        val g = source("build.gradle.kts")
        assertTrue(g.contains("applicationId = \"com.agentcontrol.app\""))
        assertTrue(g.contains("signing/agent-control-release.p12"))
        assertTrue(g.contains("keyAlias = \"agentcontrol\""))
    }
}
