package com.alex.speedshare.migration

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

internal fun sendMigrationTransferPlan(
    peer: MigrationPeer,
    totalBytes: Long,
    totalItems: Int
) {
    MigrationProtocol.connect(peer.host, peer.port).use { socket ->
        val output = BufferedOutputStream(socket.getOutputStream())
        val input = BufferedInputStream(socket.getInputStream())
        MigrationProtocol.writeJson(
            output,
            JSONObject()
                .put("type", MigrationCommands.TRANSFER_PLAN)
                .put("totalBytes", totalBytes.coerceAtLeast(0L))
                .put("totalItems", totalItems.coerceAtLeast(0))
        )
        output.flush()
        val response = MigrationProtocol.readJson(input)
        check(response.optBoolean("ok", false)) {
            response.optString("error", "transfer_plan_rejected")
        }
    }
}
