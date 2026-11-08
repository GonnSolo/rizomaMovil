package com.example.rizomamovil.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class Contact(
    val slot: Int,
    val alias: String,
    val onionAddress: String,
    val secret: String = ""
)

data class EncryptedChatMessage(
    val sender: String,
    val text: String,
    val color: String = "196",
    val timestamp: Long = System.currentTimeMillis()
)

class CryptoStorage(private val context: Context) {

    private val prefs = context.getSharedPreferences("rizoma_secure_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val SALT_FILE = "rizoma.salt"
        private const val AUTH_FILE = "rizoma_auth.enc"
        private const val CONTACTS_FILE = "rizoma_contacts.enc"
        private const val CHAT_FILE_PREFIX = "rizoma_chat_"
        private const val CHAT_FILE_SUFFIX = ".enc"

        private const val PREF_KEY_CACHED_MASTER_KEY = "cached_master_key"
        private const val PREF_KEY_REQUIRE_PASSWORD = "require_password_on_start"
        private const val PREF_KEY_MY_COLOR = "my_profile_color"
        private const val PREF_KEY_MY_NICKNAME = "my_nickname"
        private const val PREF_KEY_MY_ONION = "my_onion_address"
        private const val AUTH_MAGIC_STRING = "RIZOMA_SECURE_AUTH_VALIDATED"
    }

    private var inMemoryMasterKey: ByteArray? = null

    val isMasterKeyLoaded: Boolean
        get() = inMemoryMasterKey != null

    fun getMyColor(): String {
        return prefs.getString(PREF_KEY_MY_COLOR, "196") ?: "196"
    }

    fun setMyColor(color: String) {
        val trimmed = color.trim()
        if (trimmed.isNotEmpty()) {
            prefs.edit().putString(PREF_KEY_MY_COLOR, trimmed).apply()
        }
    }

    fun getMyNickname(): String {
        return prefs.getString(PREF_KEY_MY_NICKNAME, "MobilePeer") ?: "MobilePeer"
    }

    fun setMyNickname(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            prefs.edit().putString(PREF_KEY_MY_NICKNAME, trimmed).apply()
        }
    }

    fun getMyOnionAddress(): String {
        val existing = prefs.getString(PREF_KEY_MY_ONION, null)
        if (!existing.isNullOrEmpty()) {
            return existing
        }
        // Generate persistent 56-char base32 address if none exists yet
        val chars = "abcdefghijklmnopqrstuvwxyz234567"
        val sb = StringBuilder()
        val random = SecureRandom()
        for (i in 0 until 56) {
            sb.append(chars[random.nextInt(chars.length)])
        }
        sb.append(".onion")
        val generated = sb.toString()
        prefs.edit().putString(PREF_KEY_MY_ONION, generated).apply()
        return generated
    }

    fun setMyOnionAddress(addr: String) {
        val trimmed = addr.trim()
        if (trimmed.isNotEmpty()) {
            prefs.edit().putString(PREF_KEY_MY_ONION, trimmed).apply()
        }
    }

    /**
     * Checks if a password has already been created.
     */
    fun isPasswordConfigured(): Boolean {
        val authFile = File(context.filesDir, AUTH_FILE)
        return authFile.exists() && authFile.length() > 0
    }

    /**
     * Checks if the app is configured to require password every time it opens.
     */
    fun isRequirePasswordOnStart(): Boolean {
        return prefs.getBoolean(PREF_KEY_REQUIRE_PASSWORD, true)
    }

    /**
     * Sets whether to require password on app start.
     */
    fun setRequirePasswordOnStart(require: Boolean) {
        prefs.edit().putBoolean(PREF_KEY_REQUIRE_PASSWORD, require).apply()
        if (require) {
            prefs.edit().remove(PREF_KEY_CACHED_MASTER_KEY).apply()
        } else {
            inMemoryMasterKey?.let { key ->
                val b64 = Base64.encodeToString(key, Base64.NO_WRAP)
                prefs.edit().putString(PREF_KEY_CACHED_MASTER_KEY, b64).apply()
            }
        }
    }

    /**
     * Retrieves or generates the 32-byte salt file.
     */
    private fun getOrCreateSalt(forceNew: Boolean = false): ByteArray {
        val saltFile = File(context.filesDir, SALT_FILE)
        if (!forceNew && saltFile.exists() && saltFile.length() == 32L) {
            return saltFile.readBytes()
        }
        val newSalt = ByteArray(32)
        SecureRandom().nextBytes(newSalt)
        saltFile.writeBytes(newSalt)
        return newSalt
    }

    /**
     * Derives a 32-byte master key from password and salt using SHA-256.
     * Matches Desktop Rizoma: SHA256(salt + password).
     */
    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(password.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    /**
     * Encrypts plaintext data using AES-GCM (12-byte nonce prepended, 128-bit tag).
     */
    private fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        val cipherText = cipher.doFinal(data)
        return nonce + cipherText
    }

    /**
     * Decrypts ciphertext (12-byte nonce prepended).
     */
    private fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        if (data.size < 12 + 16) {
            throw IllegalArgumentException("Datos corruptos o tamaño inválido")
        }
        val nonce = data.copyOfRange(0, 12)
        val ciphertext = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Initial setup: sets the password, derives master key, encrypts auth token,
     * and sets preference whether to ask password on startup.
     */
    fun setupInitialPassword(password: String, requireOnStart: Boolean): Boolean {
        return try {
            val salt = getOrCreateSalt()
            val masterKey = deriveKey(password, salt)
            inMemoryMasterKey = masterKey

            // Encrypt and persist auth token
            val authCipher = encrypt(AUTH_MAGIC_STRING.toByteArray(Charsets.UTF_8), masterKey)
            File(context.filesDir, AUTH_FILE).writeBytes(authCipher)

            setRequirePasswordOnStart(requireOnStart)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Attempts automatic unlock if user chose not to require password on start.
     */
    fun tryAutoUnlock(): Boolean {
        if (isRequirePasswordOnStart()) return false
        val cachedB64 = prefs.getString(PREF_KEY_CACHED_MASTER_KEY, null) ?: return false
        return try {
            val key = Base64.decode(cachedB64, Base64.NO_WRAP)
            val authFile = File(context.filesDir, AUTH_FILE)
            if (!authFile.exists()) return false

            val decrypted = decrypt(authFile.readBytes(), key)
            if (String(decrypted, Charsets.UTF_8) == AUTH_MAGIC_STRING) {
                inMemoryMasterKey = key
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validates user-entered password against encrypted auth token.
     */
    fun unlockWithPassword(password: String): Boolean {
        return try {
            val salt = getOrCreateSalt()
            val candidateKey = deriveKey(password, salt)
            val authFile = File(context.filesDir, AUTH_FILE)
            if (!authFile.exists()) return false

            val decrypted = decrypt(authFile.readBytes(), candidateKey)
            if (String(decrypted, Charsets.UTF_8) == AUTH_MAGIC_STRING) {
                inMemoryMasterKey = candidateKey
                if (!isRequirePasswordOnStart()) {
                    val b64 = Base64.encodeToString(candidateKey, Base64.NO_WRAP)
                    prefs.edit().putString(PREF_KEY_CACHED_MASTER_KEY, b64).apply()
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resets all credentials, keys, contacts, chat histories, and preferences.
     */
    fun resetAllData(): Boolean {
        return try {
            inMemoryMasterKey = null

            // 1. Delete all encrypted files and chat histories in filesDir
            context.filesDir.listFiles()?.forEach { file ->
                if (file.name == AUTH_FILE ||
                    file.name == SALT_FILE ||
                    file.name == CONTACTS_FILE ||
                    file.name == "rizoma_config.json" ||
                    file.name.startsWith(CHAT_FILE_PREFIX)
                ) {
                    file.delete()
                }
            }

            // 2. Delete loadingbay_in files if any
            val loadingBay = File(context.getExternalFilesDir(null) ?: context.filesDir, "loadingbay_in")
            if (loadingBay.exists()) {
                loadingBay.deleteRecursively()
            }

            // 3. Clear SharedPreferences
            prefs.edit().clear().apply()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Re-encrypts all data (auth token, contacts, and all chat history) with a new password.
     */
    fun reEncryptAllWithNewPassword(oldPassword: String, newPassword: String): Boolean {
        try {
            val oldSalt = getOrCreateSalt()
            val oldKey = deriveKey(oldPassword, oldSalt)
            val authFile = File(context.filesDir, AUTH_FILE)
            if (!authFile.exists()) return false

            // 1. Verify old password
            val testDecrypted = decrypt(authFile.readBytes(), oldKey)
            if (String(testDecrypted, Charsets.UTF_8) != AUTH_MAGIC_STRING) {
                return false
            }

            // 2. Load all current data using old key
            inMemoryMasterKey = oldKey
            val contacts = loadContacts()
            val chats = mutableMapOf<String, List<EncryptedChatMessage>>()
            for (c in contacts) {
                chats[c.alias] = loadMessages(c.alias)
            }
            chats["THE VOID"] = loadMessages("THE VOID")

            // 3. Generate new salt and derive new master key
            val newSalt = getOrCreateSalt(forceNew = true)
            val newKey = deriveKey(newPassword, newSalt)
            inMemoryMasterKey = newKey

            // 4. Re-encrypt auth file
            val newAuth = encrypt(AUTH_MAGIC_STRING.toByteArray(Charsets.UTF_8), newKey)
            authFile.writeBytes(newAuth)

            // 5. Re-encrypt contacts
            val contactsFile = File(context.filesDir, CONTACTS_FILE)
            if (contacts.isNotEmpty()) {
                val jsonArray = JSONArray()
                for (c in contacts) {
                    val obj = JSONObject().apply {
                        put("slot", c.slot)
                        put("alias", c.alias)
                        put("onionAddress", c.onionAddress)
                        put("secret", c.secret)
                    }
                    jsonArray.put(obj)
                }
                val newContactsBytes = encrypt(jsonArray.toString().toByteArray(Charsets.UTF_8), newKey)
                contactsFile.writeBytes(newContactsBytes)
            } else if (contactsFile.exists()) {
                contactsFile.delete()
            }

            // 6. Re-encrypt each chat file
            for ((alias, msgs) in chats) {
                if (msgs.isNotEmpty()) {
                    val jsonArray = JSONArray()
                    for (m in msgs) {
                        val obj = JSONObject().apply {
                            put("sender", m.sender)
                            put("text", m.text)
                            put("color", m.color)
                            put("timestamp", m.timestamp)
                        }
                        jsonArray.put(obj)
                    }
                    val newChatBytes = encrypt(jsonArray.toString().toByteArray(Charsets.UTF_8), newKey)
                    getChatFile(alias).writeBytes(newChatBytes)
                }
            }

            // 7. Update cached key if auto-login is active
            if (!isRequirePasswordOnStart()) {
                val b64 = Base64.encodeToString(newKey, Base64.NO_WRAP)
                prefs.edit().putString(PREF_KEY_CACHED_MASTER_KEY, b64).apply()
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // -------------------------------------------------------------
    // Contacts Encryption & Persistence
    // -------------------------------------------------------------

    fun loadContacts(): List<Contact> {
        val key = inMemoryMasterKey ?: return emptyList()
        val file = File(context.filesDir, CONTACTS_FILE)
        if (!file.exists()) return emptyList()

        return try {
            val decryptedBytes = decrypt(file.readBytes(), key)
            val jsonStr = String(decryptedBytes, Charsets.UTF_8)
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<Contact>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    Contact(
                        slot = obj.getInt("slot"),
                        alias = obj.getString("alias"),
                        onionAddress = obj.getString("onionAddress"),
                        secret = obj.optString("secret", "")
                    )
                )
            }
            list.sortedBy { it.slot }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveContact(contact: Contact): Boolean {
        val key = inMemoryMasterKey ?: return false
        val currentContacts = loadContacts().toMutableList()
        currentContacts.removeAll { it.slot == contact.slot || it.alias.equals(contact.alias, ignoreCase = true) }
        currentContacts.add(contact)
        currentContacts.sortBy { it.slot }

        return try {
            val jsonArray = JSONArray()
            for (c in currentContacts) {
                val obj = JSONObject().apply {
                    put("slot", c.slot)
                    put("alias", c.alias)
                    put("onionAddress", c.onionAddress)
                    put("secret", c.secret)
                }
                jsonArray.put(obj)
            }
            val jsonBytes = jsonArray.toString().toByteArray(Charsets.UTF_8)
            val encrypted = encrypt(jsonBytes, key)
            File(context.filesDir, CONTACTS_FILE).writeBytes(encrypted)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun deleteContact(slot: Int): Boolean {
        val key = inMemoryMasterKey ?: return false
        val currentContacts = loadContacts().toMutableList()
        currentContacts.removeAll { it.slot == slot }

        // Re-index remaining contacts sequentially
        val reindexed = currentContacts.mapIndexed { index, contact ->
            contact.copy(slot = index + 1)
        }

        return try {
            val jsonArray = JSONArray()
            for (c in reindexed) {
                val obj = JSONObject().apply {
                    put("slot", c.slot)
                    put("alias", c.alias)
                    put("onionAddress", c.onionAddress)
                    put("secret", c.secret)
                }
                jsonArray.put(obj)
            }
            val jsonBytes = jsonArray.toString().toByteArray(Charsets.UTF_8)
            val encrypted = encrypt(jsonBytes, key)
            File(context.filesDir, CONTACTS_FILE).writeBytes(encrypted)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // -------------------------------------------------------------
    // Chat Conversations Encryption & Persistence
    // -------------------------------------------------------------

    private fun getChatFile(alias: String): File {
        val b64 = Base64.encodeToString(alias.lowercase().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val file = File(context.filesDir, "$CHAT_FILE_PREFIX$b64$CHAT_FILE_SUFFIX")
        if (file.exists()) return file
        // Fallback for legacy files
        val legacyName = alias.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        val legacyFile = File(context.filesDir, "$CHAT_FILE_PREFIX$legacyName$CHAT_FILE_SUFFIX")
        return if (legacyFile.exists()) legacyFile else file
    }

    fun loadMessages(alias: String): List<EncryptedChatMessage> {
        val key = inMemoryMasterKey ?: return emptyList()
        val file = getChatFile(alias)
        if (!file.exists()) return emptyList()

        return try {
            val decryptedBytes = decrypt(file.readBytes(), key)
            val jsonStr = String(decryptedBytes, Charsets.UTF_8)
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<EncryptedChatMessage>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    EncryptedChatMessage(
                        sender = obj.getString("sender"),
                        text = obj.getString("text"),
                        color = obj.optString("color", "196"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveMessage(alias: String, message: EncryptedChatMessage): Boolean {
        val key = inMemoryMasterKey ?: return false
        val currentMessages = loadMessages(alias).toMutableList()
        currentMessages.add(message)

        return try {
            val jsonArray = JSONArray()
            for (m in currentMessages) {
                val obj = JSONObject().apply {
                    put("sender", m.sender)
                    put("text", m.text)
                    put("color", m.color)
                    put("timestamp", m.timestamp)
                }
                jsonArray.put(obj)
            }
            val jsonBytes = jsonArray.toString().toByteArray(Charsets.UTF_8)
            val encrypted = encrypt(jsonBytes, key)
            getChatFile(alias).writeBytes(encrypted)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
