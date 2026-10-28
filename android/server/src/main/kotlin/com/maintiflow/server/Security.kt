package com.maintiflow.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Mots de passe : PBKDF2-HMAC-SHA256 avec sel aléatoire (disponible sur Android 8+ sans bibliothèque).
 * Jetons de session : 256 bits aléatoires ; seule leur empreinte SHA-256 est stockée en base.
 */
object Security {
    const val MIN_PASSWORD_LENGTH = 8
    private val random = SecureRandom()

    fun hashPassword(password: String, iterations: Int): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val hash = pbkdf2(password, salt, iterations)
        val b64 = Base64.getEncoder()
        return "pbkdf2_sha256\$$iterations\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    fun verifyPassword(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2_sha256") return false
        return runCatching {
            val dec = Base64.getDecoder()
            val expected = dec.decode(parts[3])
            MessageDigest.isEqual(pbkdf2(password, dec.decode(parts[2]), parts[1].toInt()), expected)
        }.getOrDefault(false)
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Même règle que le serveur PC : 8 caractères minimum, lettres et chiffres mélangés. */
    fun checkPasswordPolicy(password: String) {
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw ApiException(422, "Le mot de passe doit contenir au moins $MIN_PASSWORD_LENGTH caractères")
        }
        if (password.all { it.isDigit() } || password.all { it.isLetter() }) {
            throw ApiException(422, "Le mot de passe doit mélanger lettres et chiffres")
        }
    }

    fun newToken(): String {
        val b = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
}
