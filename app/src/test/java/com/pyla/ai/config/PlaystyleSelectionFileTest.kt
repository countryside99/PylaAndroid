package com.pyla.ai.config

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaystyleSelectionFileTest {
    @get:Rule val temporary = TemporaryFolder()
    private class MemoryPrefs : PrefsBackend {
        val values = LinkedHashMap<String, String>()
        override fun contains(key: String) = values.containsKey(key)
        override fun getString(key: String) = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun allKeys() = values.keys.toSet()
    }

    @Test fun retainedMissingKeyReproducesOldNoopThenPersistsWithMigration() {
        val original = "# retained user's settings\nentity_detection_confidence = 0.72\n"
        val file = temporary.newFile("bot_config.toml").apply { writeText(original) }
        val prefs = MemoryPrefs()
        val store = UserSettingsStore(prefs, { file })
        // Genuine unchanged writer: absent key is silently ignored, not a fabricated baseline.
        store.set("cfg/bot_config.toml", "current_playstyle", "custom.pyla")
        assertNull(store.getString("cfg/bot_config.toml", "current_playstyle"))
        assertEquals(original, file.readText())
        PlaystyleSelectionFile.ensureKey(file, "default.pyla")
        assertTrue(file.readText().endsWith(original))
        store.set("cfg/bot_config.toml", "current_playstyle", "custom.pyla")
        assertEquals("custom.pyla", store.getString("cfg/bot_config.toml", "current_playstyle"))
        // Disk stays authoritative when legacy preference/cache bookkeeping is absent.
        assertEquals("custom.pyla", UserSettingsStore(MemoryPrefs(), { file }).getString("cfg/bot_config.toml", "current_playstyle"))
        assertEquals("default.pyla", store.getDefaultString("cfg/bot_config.toml", "current_playstyle"))
        store.remove("cfg/bot_config.toml", "current_playstyle")
        assertEquals("default.pyla", store.getString("cfg/bot_config.toml", "current_playstyle"))
    }

    @Test fun existingSelectionIsByteIdenticalAndMigrationIsIdempotent() {
        val file = temporary.newFile("existing.toml")
        val original = "# mine\r\ncurrent_playstyle = \"kept.pyla\"\r\nentity_detection_confidence = 0.7\r\n"
        file.writeText(original)
        repeat(2) { PlaystyleSelectionFile.ensureKey(file, "default.pyla") }
        assertEquals(original, file.readText())
        assertEquals(listOf("existing.toml"), temporary.root.list()!!.toList())
    }

    @Test fun newlySeededKeyIsAtRootBeforeExistingSections() {
        val file = temporary.newFile("sections.toml").apply { writeText("[other]\nvalue = 7\n") }
        PlaystyleSelectionFile.ensureKey(file, "default.pyla")
        assertTrue(file.readText().startsWith("current_playstyle = \"default.pyla\"\n[other]"))
        assertEquals("default.pyla", TomlLite.parse(file.readText()).opt("current_playstyle"))
    }

    @Test fun legitimateParentAliasWorksLikeAndroidAppStorageNamespace() {
        val actual = temporary.newFolder("actual")
        val file = File(actual, "bot_config.toml").apply { writeText("# retained\nother = 1\n") }
        val alias = File(temporary.root, "alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), actual.toPath())
        val throughAlias = File(alias, "bot_config.toml")
        assertNotEquals(throughAlias.absoluteFile, throughAlias.canonicalFile)
        PlaystyleSelectionFile.ensureKey(throughAlias, "default.pyla")
        assertEquals("default.pyla", TomlLite.parse(file.readText()).opt("current_playstyle"))
    }

    @Test fun symlinkLeafIsRejectedWithoutChangingTarget() {
        val target = temporary.newFile("target.toml").apply { writeText("other = 1\n") }
        val leaf = File(temporary.root, "bot_config.toml")
        java.nio.file.Files.createSymbolicLink(leaf.toPath(), target.toPath())
        try {
            PlaystyleSelectionFile.ensureKey(leaf, "default.pyla")
            fail("symlink leaf must fail")
        } catch (_: java.io.IOException) {
            assertEquals("other = 1\n", target.readText())
        }
    }

    @Test fun bomConfigKeepsTheBomOutOfTheInjectedKeyAndPreservesValues() {
        val file = temporary.newFile("bom.toml").apply { writeText("\uFEFF# retained\nentity_detection_confidence = 0.72\n") }
        PlaystyleSelectionFile.ensureKey(file, "default.pyla")
        val updated = file.readText()
        assertFalse(updated.contains('\uFEFF'))
        assertEquals("default.pyla", TomlLite.parse(updated).opt("current_playstyle"))
        assertEquals(0.72, (TomlLite.parse(updated).opt("entity_detection_confidence") as Number).toDouble(), 0.00001)
        assertTrue(updated.endsWith("# retained\nentity_detection_confidence = 0.72\n"))
    }

    @Test fun missingConfigIsNotInventedAndFailureIsVisibleToCaller() {
        val file = File(temporary.root, "missing.toml")
        try {
            PlaystyleSelectionFile.ensureKey(file, "default.pyla")
            fail("missing file must fail")
        } catch (_: java.io.IOException) {
            assertFalse(file.exists())
        }
    }
}
