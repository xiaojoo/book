package com.bookreader.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 秘钥不写明文：用 AndroidKeyStore 底下一把 AES/GCM 的对称钥加密后才落 `SharedPreferences`。
 * 密文格式 = 12 字节 IV ‖ GCM 密文，整体 Base64（NO_WRAP，方便塞进一个 String 字段）。
 *
 * 两件事要说清楚：
 * - **换机 / 云恢复备份后这一串解不开**。Keystore 的密钥材料不参与备份，而本应用的
 *   `allowBackup=true`，所以恢复回来的只有密文。这时 [decrypt] 返回 null，界面要要求重填一次秘钥。
 * - 这台机是 debug 包，`run-as` 能读到 prefs 文件本身 —— 加密挡的是"文件被直接 cat 出明文"，
 *   挡不住已经拿到 root 或正在调试这个进程的人。
 */
object SecretStore {

    private const val ALIAS = "bookreader-ai"
    private const val IV_BYTES = 12
    private const val TAG = "AiSecret"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey? = runCatching {
        val ks = keyStore()
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generate()
    }.onFailure {
        // 厂商 ROM 上 Keystore 偶尔不可用：不崩，退到"秘钥存不了"，界面会说明
        Log.w(TAG, "keystore unavailable: ${it.javaClass.simpleName}")
    }.getOrNull()

    private fun generate(): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    /** @return Base64 密文；Keystore 不可用时 null（调用方要把它当成"这台机存不了秘钥"） */
    fun encrypt(plain: String): String? = runCatching {
        val k = key() ?: return@runCatching null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k)
        c.iv + c.doFinal(plain.toByteArray())
    }.onFailure { Log.w(TAG, "encrypt failed: ${it.javaClass.simpleName}") }.getOrNull()
        ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    /** @return 明文；密文不是本机的钥封的、或被改过（GCM 校验失败）时 null */
    fun decrypt(blob: String): String? = runCatching {
        val all = Base64.decode(blob, Base64.NO_WRAP)
        if (all.size <= IV_BYTES) return@runCatching null
        val k = key() ?: return@runCatching null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, all, 0, IV_BYTES))
        String(c.doFinal(all, IV_BYTES, all.size - IV_BYTES))
    }.onFailure { Log.w(TAG, "decrypt failed: ${it.javaClass.simpleName}") }.getOrNull()
}
