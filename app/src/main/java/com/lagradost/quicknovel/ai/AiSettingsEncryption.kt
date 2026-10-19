package com.lagradost.quicknovel.ai

import com.lagradost.quicknovel.DataStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned authenticated encryption; credentials never enter the normal settings/backup store. */
internal class AiSettingsEncryption(private val key: () -> SecretKey) {
    fun encode(settings: AiSettings): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == 12)
        cipher.updateAAD(byteArrayOf(1))
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(DataStore.mapper.writeValueAsBytes(settings))
    }

    fun decode(bytes: ByteArray): AiSettings {
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Invalid AI settings format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(byteArrayOf(1))
        val root = DataStore.mapper.readTree(cipher.doFinal(bytes.copyOfRange(13, bytes.size)))
        val settings = DataStore.mapper.treeToValue(root, AiSettings::class.java)
        if (root.has("profiles") || !root.has("configs")) return settings
        // Preserve settings saved by the single-key version of the editor.
        var migrated = settings
        for (provider in AiProvider.entries) {
            val legacy = root.path("configs").get(provider.name) ?: continue
            val config = DataStore.mapper.treeToValue(legacy, AiProviderConfig::class.java)
            migrated = migrated.copy(provider = provider).withConfig(config)
        }
        return migrated.copy(provider = settings.provider)
    }
}
