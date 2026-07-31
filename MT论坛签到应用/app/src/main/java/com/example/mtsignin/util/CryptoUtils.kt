package com.example.mtsignin.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 加密工具类 - 使用 Android KeyStore 安全存储密码
 */
object CryptoUtils {
    private const val KEY_ALIAS = "mt_signin_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH = 128
    
    private var secretKey: SecretKey? = null
    
    /**
     * 获取或创建密钥
     */
    private fun getOrCreateKey(): SecretKey {
        if (secretKey != null) return secretKey!!
        
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        
        // 如果密钥已存在，直接获取
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            secretKey = entry.secretKey
            return secretKey!!
        }
        
        // 创建新密钥
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build()
        
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(spec)
        secretKey = keyGenerator.generateKey()
        
        return secretKey!!
    }
    
    /**
     * 加密数据
     * @param data 原始数据
     * @return Base64编码的加密数据（IV + 密文）
     */
    fun encrypt(data: String): String {
        if (data.isEmpty()) return ""
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        
        // 将IV和密文合并
        val combined = iv + encrypted
        
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }
    
    /**
     * 解密数据
     * @param data Base64编码的加密数据
     * @return 原始数据
     */
    fun decrypt(data: String): String {
        if (data.isEmpty()) return ""
        
        val combined = Base64.decode(data, Base64.NO_WRAP)
        
        // GCM IV 固定为12字节
        val ivSize = 12
        val iv = combined.copyOfRange(0, ivSize)
        val encrypted = combined.copyOfRange(ivSize, combined.size)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
        
        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }
}