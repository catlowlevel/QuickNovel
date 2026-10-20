package com.lagradost.quicknovel.ai

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

class AiSettingsEncryptionTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val settings =
        AiSettings(
            AiProvider.CLAUDE,
            profiles =
                AiProvider.entries.associateWith { provider ->
                    listOf("first", "second").map { key ->
                        AiKeyProfile(
                            "${provider.name}-$key",
                            key,
                            AiProviderConfig(
                                "private-key-for-${provider.name}-$key",
                                "chosen-${provider.name}-$key",
                                "https://$key-${provider.name.lowercase()}.example/api/v1/",
                            ),
                        )
                    }
                },
            selectedProfiles = AiProvider.entries.associateWith { "${it.name}-second" },
        )

    @Test
    fun encryptedSettingsRoundTripEveryProvidersCredentialsModelsAndActiveChoice() {
        val key = key()
        val encryption = AiSettingsEncryption { key }
        val encoded = encryption.encode(settings)
        assertEquals(settings, encryption.decode(encoded))
        AiProvider.entries.forEach {
            assertFalse(encoded.toString(Charsets.UTF_8).contains(settings.config(it).apiKey))
        }
    }

    @Test
    fun repeatedSavesUseIndependentRandomNonces() {
        val key = key()
        val encryption = AiSettingsEncryption { key }
        val first = encryption.encode(settings)
        val second = encryption.encode(settings)
        assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
        assertEquals(settings, encryption.decode(first))
        assertEquals(settings, encryption.decode(second))
    }

    @Test
    fun tamperingWrongKeysUnsupportedFormatsAndTruncationAreRejected() {
        val key = key()
        val encryption = AiSettingsEncryption { key }
        val encoded = encryption.encode(settings)
        val altered = encoded.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        for (bytes in listOf(altered, encoded.copyOf(10), encoded.copyOf().apply { this[0] = 2 })) {
            assertTrue(runCatching { encryption.decode(bytes) }.isFailure)
        }
        val wrongKey = key()
        assertTrue(runCatching { AiSettingsEncryption { wrongKey }.decode(encoded) }.isFailure)
    }

    @Test
    fun settingsDiagnosticsDoNotExposeApiKeys() {
        assertFalse(settings.toString().contains("private-key"))
        assertFalse(settings.config().toString().contains("private-key"))
    }

    @Test
    fun existingSingleKeyEncryptedSettingsMigrateWithoutLosingAnyProviderOrModel() {
        val key = key()
        val legacy =
            """{"provider":"CLAUDE","configs":{
            "GEMINI":{"apiKey":"old-gemini","model":"gemini-model"},
            "OPENAI":{"apiKey":"old-openai","model":"openai-model"},
            "CLAUDE":{"apiKey":"old-claude","model":"claude-model"}}}"""
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD(byteArrayOf(1))
            }
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(legacy.toByteArray())
        val encryption = AiSettingsEncryption { key }
        val migrated = encryption.decode(bytes)
        assertEquals(AiProvider.CLAUDE, migrated.provider)
        for (provider in AiProvider.entries) {
            assertEquals(1, migrated.keyProfiles(provider).size)
            assertEquals(
                AiProviderConfig("old-${provider.name.lowercase()}", "${provider.name.lowercase()}-model"),
                migrated.config(provider),
            )
        }
        assertEquals(migrated, encryption.decode(encryption.encode(migrated)))
        assertEquals(migrated, encryption.decode(bytes))
    }

    @Test
    fun profilesSavedBeforeCustomUrlsKeepTheirKeysAndUseTheDefaultEndpoint() {
        val key = key()
        val old =
            """{"provider":"OPENAI","profiles":{"OPENAI":[
            {"id":"saved-id","name":"Existing key","config":{"apiKey":"existing-key","model":"existing-model"}}]},
            "selectedProfiles":{"OPENAI":"saved-id"}}"""
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD(byteArrayOf(1))
            }
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(old.toByteArray())
        val loaded = AiSettingsEncryption { key }.decode(bytes)
        assertEquals(AiProvider.OPENAI, loaded.provider)
        assertEquals("saved-id", loaded.profile().id)
        assertEquals("Existing key", loaded.profile().name)
        assertEquals(AiProviderConfig("existing-key", "existing-model", ""), loaded.config())
    }
}
