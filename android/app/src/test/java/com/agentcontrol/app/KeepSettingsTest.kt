package com.agentcontrol.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards "update without re-entering keys". Saved keys survive an update only if the
 * new version looks for them under exactly the same names, and the app is signed
 * with the same permanent key. If this test fails, a change would wipe saved keys.
 */
class KeepSettingsTest {

    private fun source(path: String) = File(path).readText()

    @Test fun storageNamesNeverChange() {
        val s = source("src/main/java/com/agentcontrol/app/SecureStore.kt")
        assertTrue(s.contains("KEY_ALIAS = \"agent_control_settings_key_v2\""))
        assertTrue(s.contains("PREFS = \"secure_settings\""))
        assertTrue(s.contains("FIELD = \"blob_v2\""))
        for (field in listOf("accountSid", "apiKeySid", "apiKeySecret", "elevenLabsKey")) {
            assertTrue("JSON field $field renamed", s.contains("\"$field\""))
        }
    }

    @Test fun appIdAndSigningKeyNeverChange() {
        val g = source("build.gradle.kts")
        assertTrue(g.contains("applicationId = \"com.agentcontrol.app\""))
        assertTrue(g.contains("signing/agent-control-release.p12"))
        assertTrue(g.contains("keyAlias = \"agentcontrol\""))
    }
}
