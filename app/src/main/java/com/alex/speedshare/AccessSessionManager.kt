package com.alex.speedshare

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

internal class AccessSessionManager {
    private val secureRandom = SecureRandom()
    private val tokens = ConcurrentHashMap.newKeySet<String>()

    fun create(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tokens.add(token)
        return token
    }

    fun isValid(cookieHeader: String?): Boolean {
        val token = tokenFromCookie(cookieHeader) ?: return false
        return tokens.contains(token)
    }

    fun revoke(cookieHeader: String?) {
        tokenFromCookie(cookieHeader)?.let(tokens::remove)
    }

    fun clear() {
        tokens.clear()
    }

    internal fun tokenFromCookie(cookieHeader: String?): String? {
        return cookieHeader
            ?.split(';')
            ?.asSequence()
            ?.map(String::trim)
            ?.mapNotNull { item ->
                val separator = item.indexOf('=')
                if (separator <= 0) null else item.substring(0, separator) to item.substring(separator + 1)
            }
            ?.firstOrNull { (name, _) -> name == COOKIE_NAME }
            ?.second
            ?.takeIf { it.length in 32..128 && it.all(::isCookieTokenCharacter) }
    }

    private fun isCookieTokenCharacter(char: Char): Boolean {
        return char.isLetterOrDigit() || char == '-' || char == '_'
    }

    companion object {
        const val COOKIE_NAME = "speedshare_session"
        private const val TOKEN_BYTES = 32
    }
}
