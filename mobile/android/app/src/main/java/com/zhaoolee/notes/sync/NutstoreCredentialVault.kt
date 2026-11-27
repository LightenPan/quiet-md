package com.zhaoolee.notes.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.GsonBuilder
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface NutstoreCredentialStore {
    fun save(config: WebDavConfig, password: String)
    fun load(config: WebDavConfig): String?
    fun delete(config: WebDavConfig)
}

class AndroidNutstoreCredentialVault(context: Context) : NutstoreCredentialStore {
    private val prefsFile = File(context.noBackupFilesDir, "sync/nutstore-credentials.json")
    private val gson = GsonBuilder().create()
    private val lock = Any()

    override fun save(config: WebDavConfig, password: String) {
        synchronized(lock) {
            val map = read()
            map[credentialKey(config)] = encrypt(password)
            write(map)
        }
    }

    override fun load(config: WebDavConfig): String? = synchronized(lock) {
        read()[credentialKey(config)]?.let { decrypt(it) }
    }

    override fun delete(config: WebDavConfig) {
        synchronized(lock) {
            val map = read()
            if (map.remove(credentialKey(config)) != null) write(map)
        }
    }

    private fun credentialKey(config: WebDavConfig): String {
        val value = "${SyncPaths.normalizeServer(config.server)}\n${config.username.trim()}"
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun read(): LinkedHashMap<String, String> {
        if (!prefsFile.isFile) return linkedMapOf()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<LinkedHashMap<String, String>>() {}.type
            gson.fromJson<LinkedHashMap<String, String>>(prefsFile.readText(Charsets.UTF_8), type)
                ?: linkedMapOf()
        } catch (_: Exception) {
            runCatching {
                prefsFile.renameTo(File(prefsFile.parentFile, "${prefsFile.name}.corrupt-${System.currentTimeMillis()}"))
            }
            linkedMapOf()
        }
    }

    private fun write(values: Map<String, String>) {
        atomicWrite(prefsFile, gson.toJson(values).toByteArray(Charsets.UTF_8))
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(cipher.iv.size + ciphertext.size)
        cipher.iv.copyInto(packed)
        ciphertext.copyInto(packed, cipher.iv.size)
        return Base64.getEncoder().encodeToString(packed)
    }

    private fun decrypt(value: String): String {
        return try {
            val packed = Base64.getDecoder().decode(value)
            require(packed.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            throw SyncException("无法读取已保存的坚果云应用密码，请重新连接", e)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null, null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "quiet-md-nutstore-v1"
    }
}
