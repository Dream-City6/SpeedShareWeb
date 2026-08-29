package com.alex.speedshare.migration

import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

data class MigrationCryptoSessionInfo(
    val peerDeviceId: String,
    val securityCode: String,
    val establishedAt: Long
)

internal object MigrationCryptoSessionRegistry {
    private data class Entry(
        val key: SecretKey,
        val info: MigrationCryptoSessionInfo
    )

    private val sessions = ConcurrentHashMap<String, Entry>()

    fun register(token: String, peerDeviceId: String, key: SecretKey): MigrationCryptoSessionInfo {
        require(token.isNotBlank())
        val info = MigrationCryptoSessionInfo(
            peerDeviceId = peerDeviceId,
            securityCode = MigrationCrypto.securityCode(key),
            establishedAt = System.currentTimeMillis()
        )
        sessions[token] = Entry(key, info)
        return info
    }

    fun key(token: String): SecretKey? = sessions[token]?.key

    fun info(token: String): MigrationCryptoSessionInfo? = sessions[token]?.info

    fun infoForPeer(peerDeviceId: String): MigrationCryptoSessionInfo? =
        sessions.values.asSequence()
            .map { it.info }
            .filter { it.peerDeviceId == peerDeviceId }
            .maxByOrNull { it.establishedAt }

    fun isEncrypted(token: String): Boolean = sessions.containsKey(token)

    fun remove(token: String) {
        sessions.remove(token)
    }

    fun clear() {
        sessions.clear()
    }
}
