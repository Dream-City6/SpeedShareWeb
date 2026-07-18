package com.alex.speedshare

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

internal class AccessSessionManager(
    private val sessionTtlMs: Long = DEFAULT_SESSION_TTL_MS,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    init {
        require(sessionTtlMs > 0L) { "sessionTtlMs must be greater than zero" }
        require(maxSessions > 0) { "maxSessions must be greater than zero" }
    }

    private val secureRandom = SecureRandom()
    private val tokens = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun create(): String {
        val now = nowMs()
        pruneExpired(now)
        while (tokens.size >= maxSessions) {
            val oldest = tokens.entries.minByOrNull { it.value } ?: break
            tokens.remove(oldest.key, oldest.value)
        }

        val expiresAt = now + sessionTtlMs
        while (true) {
            val bytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            if (tokens.putIfAbsent(token, expiresAt) == null) return token
        }
    }

    fun isValid(cookieHeader: String?): Boolean {
        val token = tokenFromCookie(cookieHeader) ?: return false
        val expiresAt = tokens[token] ?: return false
        val now = nowMs()
        if (expiresAt <= now) {
            tokens.remove(token, expiresAt)
            return false
        }
        return true
    }

    fun revoke(cookieHeader: String?) {
        tokenFromCookie(cookieHeader)?.let(tokens::remove)
    }

    fun clear() {
        tokens.clear()
    }

    internal fun activeSessionCount(): Int {
        pruneExpired(nowMs())
        return tokens.size
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

    private fun pruneExpired(now: Long) {
        tokens.forEach { (token, expiresAt) ->
            if (expiresAt <= now) tokens.remove(token, expiresAt)
        }
    }

    private fun isCookieTokenCharacter(char: Char): Boolean {
        return char.isLetterOrDigit() || char == '-' || char == '_'
    }

    companion object {
        const val COOKIE_NAME = "speedshare_session"
        private const val TOKEN_BYTES = 32
        private const val DEFAULT_SESSION_TTL_MS = 12L * 60L * 60L * 1000L
        private const val DEFAULT_MAX_SESSIONS = 64
    }
}
