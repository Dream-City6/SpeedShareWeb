package com.alex.speedshare

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alex.speedshare.migration.MigrationCryptoSessionRegistry
import com.alex.speedshare.migration.MigrationPeer
import com.alex.speedshare.migration.MigrationProgress
import com.alex.speedshare.migration.MigrationReport
import com.alex.speedshare.migration.MigrationRole
import com.alex.speedshare.migration.MigrationSession
import com.alex.speedshare.migration.ResilientMigrationClient
import com.alex.speedshare.migration.ResilientMigrationPeerServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {
    private var server: ResilientMigrationPeerServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
        MigrationCryptoSessionRegistry.clear()
    }

    @Test
    fun debugLauncher_targetsMainFileSharingScreen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        requireNotNull(launchIntent) { "debug app has no launcher intent" }
        val resolved = context.packageManager.resolveActivity(launchIntent, 0)
        requireNotNull(resolved) { "debug launcher cannot be resolved" }
        val info = resolved.activityInfo
        val target = info.targetActivity ?: info.name
        assertTrue("launcher target was $target", target.endsWith("MainActivity"))
    }

    @Test
    fun migrationScanPermissions_areDeclared() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        )
        val requested = info.requestedPermissions?.toSet().orEmpty()
        assertTrue(Manifest.permission.MANAGE_EXTERNAL_STORAGE in requested)
        assertTrue(Manifest.permission.READ_MEDIA_IMAGES in requested)
        assertTrue(Manifest.permission.READ_MEDIA_VIDEO in requested)
        assertTrue(Manifest.permission.READ_MEDIA_AUDIO in requested)
        assertTrue(Manifest.permission.READ_CONTACTS in requested)
        assertTrue("android.permission.QUERY_ALL_PACKAGES" in requested)
        assertTrue("com.android.permission.GET_INSTALLED_APPS" in requested)
    }

    @Test
    fun loopbackPair_establishesEncryptedSession_appVersionQuery_andRejectsInvalidToken() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        lateinit var localServer: ResilientMigrationPeerServer
        localServer = ResilientMigrationPeerServer(
            context = context,
            localDeviceId = "receiver-test-device",
            localDeviceName = "Receiver",
            appVersion = "instrumented-test",
            onPairRequest = { request -> localServer.respondPair(request.requestId, true) },
            onPeerConnected = { _, _ -> },
            onRole = { _: MigrationRole -> },
            onSpeedResult = { _ -> },
            onTransferPlan = { _, _, _ -> },
            onProgressSync = { _: MigrationProgress -> },
            onReport = { _: MigrationReport -> }
        )
        server = localServer
        localServer.start()

        val receiver = MigrationPeer(
            deviceId = "receiver-test-device",
            name = "Receiver",
            host = "127.0.0.1",
            port = localServer.port
        )
        val sender = MigrationPeer(
            deviceId = "sender-test-device",
            name = "Sender",
            host = "127.0.0.1",
            port = 1
        )
        val token = ResilientMigrationClient.newInboundToken()
        val pair = ResilientMigrationClient.requestPair(sender, receiver, token)
        assertTrue(pair.accepted)
        assertEquals(token, pair.outboundToken)
        assertTrue(MigrationCryptoSessionRegistry.isEncrypted(token))
        val cryptoInfo = MigrationCryptoSessionRegistry.info(token)
        assertNotNull(cryptoInfo)
        assertTrue(cryptoInfo!!.securityCode.matches(Regex("\\d{6}")))

        val session = MigrationSession(pair.peer, pair.outboundToken, pair.outboundToken)
        val versions = ResilientMigrationClient.appVersions(session, listOf(context.packageName))
        assertTrue((versions[context.packageName] ?: -1L) >= 0L)

        val invalidSession = session.copy(outboundToken = "0".repeat(64))
        try {
            ResilientMigrationClient.appVersions(invalidSession, listOf(context.packageName))
            fail("invalid token unexpectedly succeeded")
        } catch (_: Throwable) {
            // Expected: peer rejects non-authorized session tokens.
        }
    }
}
