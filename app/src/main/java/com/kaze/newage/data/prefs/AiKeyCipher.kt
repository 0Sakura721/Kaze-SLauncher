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
 * 安全边界：
 *  - Keystore 初始化/加解密任何一步失败 → 返回 null；调用方必须拒绝持久化，不能明文降级；
 *  - 历史明文值（无 "enc1:" 前缀）decrypt 时原样返回，迁移到隔离存储后尽力升级为密文；
 *  - 密文损坏 / Keystore 密钥丢失 → 返回 null，用户需要重新填写 Key。
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

    /**
     * 只创建 Cipher，不提前 init。
     * GCM 解密必须在 init 时提供密文对应的 IV；若在此处先以无 IV 的方式 init，
     * Android Keystore 可能直接拒绝初始化，导致所有已保存的 enc1 密钥都无法读取。
     */
    private fun cipher(): Cipher? =
        runCatching { Cipher.getInstance("AES/GCM/NoPadding") }.getOrNull()

    /** 加密；Keystore 不可用返回 null（调用方必须拒绝明文降级） */
    fun encrypt(plain: String): String? {
        if (plain.isBlank()) return null
        val secretKey = key ?: return null
        val c = cipher() ?: return null
        return runCatching {
            c.init(Cipher.ENCRYPT_MODE, secretKey)
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
            val secretKey = key ?: return null
            val c = cipher() ?: return null
            c.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, b64(parts[0])))
            String(c.doFinal(b64(parts[1])), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP or Base64.NO_PADDING)
}
