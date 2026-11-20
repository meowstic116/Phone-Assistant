package com.phoneassistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User-managed logins encrypted with Android Keystore. The phone agent cannot access them. */
object Vault {

    /** One saved login, exactly as the user entered it. */
    data class Login(val site: String, val username: String, val password: String)

    private const val PREFS = "vault"
    private const val KEY = "logins"
    private const val ALIAS = "pip_vault"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TAG_BITS = 128

    /** GCM's own nonce length. It is stored in front of the ciphertext. */
    private const val IV_BYTES = 12

    private const val MAX_LOGINS = 60

    // null means unavailable, not empty. Never overwrite stored data after a failed read.
    @Synchronized
    fun all(ctx: Context): List<Login>? = read(ctx)

    enum class SaveResult { SAVED, INVALID, FULL, UNAVAILABLE }

    /** Adds or replaces the login for a site without silently evicting older logins. */
    @Synchronized
    fun save(ctx: Context, site: String, username: String, password: String): SaveResult {
        val cleanSite = site.trim()
        val cleanUser = username.trim()
        val siteHost = host(cleanSite)
        if (siteHost.isEmpty() || siteHost.any { it.isWhitespace() } || cleanSite.length > 200 ||
            cleanUser.isEmpty() || cleanUser.length > 120 || password.isEmpty()) return SaveResult.INVALID
        val current = read(ctx) ?: return SaveResult.UNAVAILABLE
        val kept = current.filterNot { host(it.site) == siteHost }
        if (kept.size >= MAX_LOGINS) return SaveResult.FULL
        return if (write(ctx, kept + Login(cleanSite, cleanUser, password))) SaveResult.SAVED else SaveResult.UNAVAILABLE
    }

    @Synchronized
    fun remove(ctx: Context, site: String): Boolean {
        val current = read(ctx) ?: return false
        return write(ctx, current.filterNot { host(it.site) == host(site) })
    }

    /**
     * The site as a host: lower case, no scheme, no "www.", no port, no path. It is only used
     * to match one entry with another, so a login saved from a link the user pasted still
     * answers when the model names the bare domain.
     */
    fun host(raw: String): String {
        var text = raw.trim().lowercase()
        text = text.substringAfter("://", text)
        text = text.substringBefore('/')
        text = text.substringBefore('?')
        text = text.substringBefore('#')
        text = text.substringBefore(':')
        return text.removePrefix("www.").trim('.')
    }

    // ------------------------------------------------------------------- storage

    private fun read(ctx: Context): List<Login>? {
        return try {
            val blob = prefs(ctx).getString(KEY, null) ?: return emptyList()
            val json = JSONArray(String(decrypt(blob), Charsets.UTF_8))
            (0 until json.length()).map { index ->
                val item = json.getJSONObject(index)
                val site = item.getString("site")
                val username = item.getString("username")
                val password = item.getString("password")
                check(site.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty())
                Login(site, username, password)
            }
        } catch (e: Exception) {
            Diag.vaultFailure(e)
            null
        }
    }

    private fun write(ctx: Context, logins: List<Login>): Boolean {
        val edit = prefs(ctx).edit()
        if (logins.isEmpty()) {
            edit.remove(KEY).apply()
            return true
        }
        val array = JSONArray()
        logins.forEach { login ->
            array.put(
                JSONObject()
                    .put("site", login.site)
                    .put("username", login.username)
                    .put("password", login.password)
            )
        }
        return try {
            edit.putString(KEY, encrypt(array.toString().toByteArray(Charsets.UTF_8))).apply()
            true
        } catch (e: Exception) {
            Diag.vaultFailure(e)
            false
        }
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain)
        // The nonce is not a secret, and it has to be kept: the same one twice would break
        // the cipher that protects everything else.
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): ByteArray {
        val packed = Base64.decode(blob, Base64.NO_WRAP)
        if (packed.size <= IV_BYTES) throw IllegalArgumentException("vault blob is too short")
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES))
        return cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
