package com.kaze.newage.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AI Key 的 Keystore 加密存储。
 *
 * SharedPreferences 是明文的（root 可直接读），API Key 放在里面等于依赖沙箱权限这一层。
 * 这里用 Android Keystore 里的 AES-256-GCM 密钥把 Key 包一层：密钥材料不出安全硬件，
 * 拿到 prefs 文件也解不开。
 *
 * 兜底原则（都不能让用户被锁死）：
 *  - Keystore 初始化/加解密任何一步失败 → 返回 null，调用方回退**明文**存储；
 *  - 历史明文值（无 "enc1:" 前缀）decrypt 时原样返回，下次保存才升级为密文；
 *  - 密文损坏 → 返回 null（调用方视为未配置，用户重填一次即可）。
 *
 * 标记格式：`enc1:<base64(iv)>:<base64(ciphertext)>`
 */
object AiKeyCipher {

    private const val ALIAS = "kaze_ai_prefs_key"
    private const val MARKER = "enc1:"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private val key: SecretKey? by lazy {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey) ?: run {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                gen.generateKey()
            }
        }.getOrNull()
    }

    private fun cipher(mode: Int): Cipher? = key?.let {
        runCatching { Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, it) } }.getOrNull()
    }

    /** 加密；Keystore 不可用返回 null（调用方回退明文） */
    fun encrypt(plain: String): String? {
        if (plain.isBlank()) return null
        val c = cipher(Cipher.ENCRYPT_MODE) ?: return null
        return runCatching {
            val iv = c.iv
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            MARKER + b64(iv) + ":" + b64(ct)
        }.getOrNull()
    }

    /**
     * 解密。非 "enc1:" 前缀 = 历史明文，原样返回；密文损坏/Keystore 丢失返回 null。
     */
    fun decrypt(stored: String): String? {
        if (!stored.startsWith(MARKER)) return stored.takeIf { it.isNotBlank() }
        return runCatching {
            val parts = stored.removePrefix(MARKER).split(":")
            require(parts.size == 2)
            val c = cipher(Cipher.DECRYPT_MODE) ?: return null
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, b64(parts[0])))
            String(c.doFinal(b64(parts[1])), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP or Base64.NO_PADDING)
}
