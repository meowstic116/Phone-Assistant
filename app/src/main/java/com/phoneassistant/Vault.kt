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

    @Synchronized
    fun all(ctx: Context): List<Login> = read(ctx)

    /** Adds or replaces the login for a site: one per site, so saving again is changing it. */
    @Synchronized
    fun save(ctx: Context, site: String, username: String, password: String): Boolean {
        val cleanSite = site.trim().take(200)
        val cleanUser = username.trim().take(120)
        if (cleanSite.isEmpty() || cleanUser.isEmpty() || password.isEmpty()) return false
        val kept = all(ctx).filterNot { host(it.site) == host(cleanSite) }
        return write(ctx, (kept + Login(cleanSite, cleanUser, password)).takeLast(MAX_LOGINS))
    }

    @Synchronized
    fun remove(ctx: Context, site: String): Boolean =
        write(ctx, all(ctx).filterNot { host(it.site) == host(site) })

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

    private fun read(ctx: Context): List<Login> {
        val blob = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val json = JSONArray(String(decrypt(blob), Charsets.UTF_8))
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                val site = item.optString("site")
                val username = item.optString("username")
                val password = item.optString("password")
                if (site.isEmpty() || password.isEmpty()) null else Login(site, username, password)
            }
        } catch (t: Throwable) {
            // A vault that cannot be opened is empty, not broken: it must never fall back to
            // anything less protected than what it was stored with.
            Diag.caught("vault", t)
            emptyList()
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
        } catch (t: Throwable) {
            Diag.caught("vault", t)
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
