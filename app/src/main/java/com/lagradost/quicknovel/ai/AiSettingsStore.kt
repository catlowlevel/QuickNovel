package com.lagradost.quicknovel.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AiSettingsStore(context: Context) : AiSettingsPersistence {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "ai_provider_settings.enc"))
    private val encryption = AiSettingsEncryption(::key)

    override suspend fun load(): AiSettings = withContext(Dispatchers.IO) {
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return@withContext AiSettings() }
        encryption.decode(bytes)
    }

    override suspend fun save(settings: AiSettings): Unit = withContext(Dispatchers.IO) {
        val bytes = encryption.encode(settings)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val alias = "quicknovel_ai_settings_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
}
