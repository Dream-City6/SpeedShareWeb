package com.alex.speedshare.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MigrationCoreTest {
    @Test
    fun normalizeRelativePath_rejectsTraversal() {
        assertNull(normalizeRelativePath("../secret.txt"))
        assertNull(normalizeRelativePath("DCIM/../../secret.txt"))
        assertNull(normalizeRelativePath("./photo.jpg"))
    }

    @Test
    fun normalizeRelativePath_normalizesSeparators() {
        assertEquals("DCIM/Camera/a.jpg", normalizeRelativePath("/DCIM\\Camera/a.jpg/"))
    }

    @Test
    fun speedResult_averageUsesBothDirections() {
        val result = SpeedTestResult(
            latencyMs = 10,
            uploadBytesPerSecond = 40,
            downloadBytesPerSecond = 60,
            stabilityPercent = 95
        )
        assertEquals(50L, result.averageBytesPerSecond)
    }

    @Test
    fun scanResult_countsCategories() {
        val files = listOf(
            MigrationFileItem(File("a.jpg"), "DCIM/a.jpg", 100, 0, MigrationCategory.PHOTOS),
            MigrationFileItem(File("b.jpg"), "DCIM/b.jpg", 200, 0, MigrationCategory.PHOTOS),
            MigrationFileItem(File("c.pdf"), "Documents/c.pdf", 50, 0, MigrationCategory.DOCUMENTS)
        )
        val result = MigrationScanResult(files = files)
        assertEquals(2, result.count(MigrationCategory.PHOTOS))
        assertEquals(300L, result.bytes(MigrationCategory.PHOTOS))
        assertEquals(1, result.count(MigrationCategory.DOCUMENTS))
    }

    @Test
    fun resilientScanner_prioritizesStandardDirectories() {
        assertEquals(
            MigrationCategory.DOWNLOADS,
            MigrationScannerV2.categoryFor("Download/photo.jpg", "photo.jpg")
        )
        assertEquals(
            MigrationCategory.DOCUMENTS,
            MigrationScannerV2.categoryFor("Documents/video.mp4", "video.mp4")
        )
        assertEquals(
            MigrationCategory.PHOTOS,
            MigrationScannerV2.categoryFor("DCIM/Camera/raw.bin", "raw.bin")
        )
        assertEquals(
            MigrationCategory.VIDEOS,
            MigrationScannerV2.categoryFor("Movies/readme.txt", "readme.txt")
        )
    }

    @Test
    fun resilientScanner_usesExtensionOutsideStandardDirectories() {
        assertEquals(
            MigrationCategory.PHOTOS,
            MigrationScannerV2.categoryFor("Tencent/cache/picture.webp", "picture.webp")
        )
        assertEquals(
            MigrationCategory.VIDEOS,
            MigrationScannerV2.categoryFor("CameraExports/clip.mkv", "clip.mkv")
        )
        assertEquals(
            MigrationCategory.OTHER,
            MigrationScannerV2.categoryFor("Backup/blob.dat", "blob.dat")
        )
    }

    @Test
    fun resilientScanner_classifiesMediaInsideCameraByExtension() {
        assertEquals(MigrationCategory.VIDEOS, MigrationScannerV2.categoryFor("DCIM/Camera/clip.mp4", "clip.mp4"))
        assertEquals(MigrationCategory.VIDEOS, MigrationScannerV2.categoryFor("DCIM/Camera/clip.MKV", "clip.MKV"))
        assertEquals(MigrationCategory.PHOTOS, MigrationScannerV2.categoryFor("DCIM/Camera/photo.JPG", "photo.JPG"))
    }

    @Test
    fun resilientPairToken_isRandomAndStrongLength() {
        val first = ResilientMigrationClient.newInboundToken()
        val second = ResilientMigrationClient.newInboundToken()
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(first, second)
    }

    @Test
    fun migrationHashCache_returnsStableSha256() {
        val file = Files.createTempFile("speedshare-hash", ".bin").toFile()
        try {
            file.writeText("SpeedShare migration")
            val first = MigrationHashCache.sha256(file)
            val second = MigrationHashCache.sha256(file)
            assertEquals(64, first.length)
            assertEquals(first, second)
        } finally {
            file.delete()
            MigrationHashCache.clear()
        }
    }

    @Test
    fun recommendedPreset_keepsImportantContentButSkipsCatchAllBuckets() {
        val recommended = MigrationSelectionCalculator.presetCategories(MigrationSelectionPreset.RECOMMENDED)
        assertTrue(MigrationCategory.PHOTOS in recommended)
        assertTrue(MigrationCategory.VIDEOS in recommended)
        assertFalse(MigrationCategory.DOCUMENTS in recommended)
        assertFalse(MigrationCategory.DOWNLOADS in recommended)
        assertTrue(MigrationCategory.APPS in recommended)
        assertTrue(MigrationCategory.MUSIC in recommended)
        assertFalse(MigrationCategory.OTHER in recommended)
        assertEquals(MigrationCategory.entries.toSet(), MigrationSelectionCalculator.presetCategories(MigrationSelectionPreset.ALL))
    }

    @Test
    fun fileSelectionRegistry_defaultsEmptyAndCanSelectIndividualPath() {
        val files = listOf(
            MigrationFileItem(File("keep.pdf"), "Documents/keep.pdf", 10, 0, MigrationCategory.DOCUMENTS),
            MigrationFileItem(File("drop.pdf"), "Documents/drop.pdf", 20, 0, MigrationCategory.DOCUMENTS),
            MigrationFileItem(File("photo.jpg"), "DCIM/photo.jpg", 30, 0, MigrationCategory.PHOTOS)
        )
        MigrationFileSelectionRegistry.sync(files)
        MigrationFileSelectionRegistry.selectNone()
        MigrationFileSelectionRegistry.toggle("Documents/keep.pdf")
        val result = MigrationFileSelectionRegistry.filterTransferItems(files)
        assertTrue(result.any { it.relativePath == "Documents/keep.pdf" })
        assertFalse(result.any { it.relativePath == "Documents/drop.pdf" })
        assertTrue(result.any { it.relativePath == "DCIM/photo.jpg" })
        MigrationFileSelectionRegistry.selectNone()
    }

    @Test
    fun recommendedSelection_doesNotIncludeUncheckedDownloadFiles() {
        val files = listOf(
            MigrationFileItem(File("photo.jpg"), "DCIM/Camera/photo.jpg", 10, 0, MigrationCategory.PHOTOS),
            MigrationFileItem(File("private.zip"), "Download/private.zip", 20, 0, MigrationCategory.DOWNLOADS)
        )
        MigrationFileSelectionRegistry.sync(files)
        MigrationFileSelectionRegistry.selectNone()
        MigrationMediaSelectionRegistry.sync(files)

        val result = MigrationSelectionCalculator.effectiveItems(
            MigrationScanResult(files = files),
            MigrationSelectionCalculator.presetCategories(MigrationSelectionPreset.RECOMMENDED)
        )

        assertTrue(result.items.any { it.relativePath == "DCIM/Camera/photo.jpg" })
        assertFalse(result.items.any { it.relativePath == "Download/private.zip" })
    }

    @Test
    fun explicitFolderSelection_canIncludeAFileOutsideCheckedCategories() {
        val file = MigrationFileItem(File("manual.pdf"), "Documents/manual.pdf", 20, 0, MigrationCategory.DOCUMENTS)
        MigrationFileSelectionRegistry.sync(listOf(file))
        MigrationFileSelectionRegistry.selectNone()
        MigrationFileSelectionRegistry.toggle(file.relativePath)

        val result = MigrationSelectionCalculator.effectiveItems(MigrationScanResult(files = listOf(file)), emptySet())

        assertEquals(listOf(file.relativePath), result.items.map { it.relativePath })
        MigrationFileSelectionRegistry.selectNone()
    }

    @Test
    fun manualEndpoint_acceptsValidIpv4AndPort() {
        assertEquals("192.168.1.23" to 47999, parseMigrationEndpoint("192.168.1.23:47999"))
        assertEquals("10.0.0.8" to 12345, parseMigrationEndpoint("http://10.0.0.8:12345/"))
    }

    @Test
    fun manualEndpoint_rejectsInvalidIpv4OrPort() {
        assertNull(parseMigrationEndpoint("192.168.1.23"))
        assertNull(parseMigrationEndpoint("192.168.1.300:47999"))
        assertNull(parseMigrationEndpoint("192.168.1.23:0"))
        assertNull(parseMigrationEndpoint("192.168.1.23:70000"))
        assertNull(parseMigrationEndpoint("not-an-ip:47999"))
    }

    @Test
    fun wifiQrPayload_escapesReservedCharacters() {
        assertEquals(
            "WIFI:T:WPA;S:Speed\\;Share;P:p\\:a\\,s\\\\s;;",
            wifiQrPayload("Speed;Share", "p:a,s\\s")
        )
        assertEquals("WIFI:T:nopass;S:Open;P:;;", wifiQrPayload("Open", ""))
    }

    @Test
    fun wifiQrPayload_roundTripsIntoConnectionCredentials() {
        val parsed = parseWifiQrPayload(wifiQrPayload("Speed;Share", "p:a,s\\word"))

        assertEquals("Speed;Share", parsed?.ssid)
        assertEquals("p:a,s\\word", parsed?.password)
        assertEquals("WPA", parsed?.security)

        val wpa3 = parseWifiQrPayload(wifiQrPayload("SpeedShare-6G", "securepass", "WPA3"))
        assertEquals("WPA3", wpa3?.security)
        assertEquals("securepass", wpa3?.password)

        val targeted = parseWifiQrPayload(
            wifiQrPayload("SpeedShare", "securepass", "WPA", "device-123", 47999, "pair-token")
        )
        assertEquals("device-123", targeted?.targetDeviceId)
        assertEquals(47999, targeted?.targetPort)
        assertEquals("pair-token", targeted?.targetPairToken)
    }

    @Test
    fun wifiQrPayload_rejectsNonWifiAndInvalidProtectedNetworks() {
        assertNull(parseWifiQrPayload("https://example.com"))
        assertNull(parseWifiQrPayload("WIFI:T:WEP;S:Legacy;P:12345678;;"))
        assertNull(parseWifiQrPayload("WIFI:T:WPA;S:SpeedShare;P:short;;"))
    }

    @Test
    fun hotspotLifecycle_onlyKeepsHotspotForAnActiveTransfer() {
        assertTrue(shouldKeepHotspotForStage(MigrationStage.TRANSFERRING))
        assertTrue(shouldKeepHotspotForStage(MigrationStage.VERIFYING))
        assertFalse(shouldKeepHotspotForStage(MigrationStage.DISCOVERY))
        assertFalse(shouldKeepHotspotForStage(MigrationStage.SELECTION))
        assertFalse(shouldKeepHotspotForStage(MigrationStage.COMPLETE))
    }

    @Test
    fun directHotspotAutoConnect_onlySelectsOneUnattemptedPeer() {
        val peer = MigrationPeer("peer-1", "Phone", "192.168.43.2", 47999)
        val ready = ResilientMigrationState(peers = listOf(peer))

        assertEquals(peer, directHotspotAutoConnectPeer(true, ready, null))
        assertNull(directHotspotAutoConnectPeer(false, ready, null))
        assertNull(directHotspotAutoConnectPeer(true, ready, peer.deviceId))
        assertNull(directHotspotAutoConnectPeer(true, ready.copy(pairing = true), null))
        assertNull(directHotspotAutoConnectPeer(true, ready.copy(peers = listOf(peer, peer.copy(deviceId = "peer-2"))), null))
    }

    @Test
    fun automaticPairing_directJoinerAlwaysInitiatesButSharedWifiUsesOneDeterministicSide() {
        val peer = MigrationPeer("peer-z", "Phone", "192.168.43.1", 47999)
        val lowerId = ResilientMigrationState(localDeviceId = "peer-a", peers = listOf(peer))
        val higherId = lowerId.copy(localDeviceId = "peer-zz")

        assertTrue(shouldAutomaticallyInitiatePair(false, true, false, higherId))
        assertTrue(shouldAutomaticallyInitiatePair(false, false, false, lowerId))
        assertFalse(shouldAutomaticallyInitiatePair(false, false, false, higherId))
        assertFalse(shouldAutomaticallyInitiatePair(true, false, false, lowerId))
        assertFalse(shouldAutomaticallyInitiatePair(false, false, true, lowerId))
        assertFalse(shouldAutomaticallyInitiatePair(false, false, false, lowerId.copy(peers = listOf(peer, peer.copy(deviceId = "peer-y")))))
    }

    @Test
    fun qrPairToken_bindsToTheFirstRequestingDevice() {
        assertTrue(canClaimPairToken(true, "token", "", "token", "phone-a"))
        assertTrue(canClaimPairToken(true, "token", "phone-a", "token", "phone-a"))
        assertFalse(canClaimPairToken(true, "token", "phone-a", "token", "phone-b"))
        assertFalse(canClaimPairToken(false, "token", "", "token", "phone-a"))
        assertFalse(canClaimPairToken(true, "token", "", "wrong", "phone-a"))
    }

    @Test
    fun roleChooser_isDeterministicAndUnique() {
        assertTrue(isRoleChooser("device-a", "device-b"))
        assertFalse(isRoleChooser("device-b", "device-a"))
        assertFalse(isRoleChooser("", "device-a"))
        assertFalse(isRoleChooser("device-a", "device-a"))
    }

    @Test
    fun sourceValidator_reportsDeletedAndChangedFiles() {
        val file = Files.createTempFile("speedshare-source", ".bin").toFile()
        try {
            file.writeBytes(ByteArray(32) { it.toByte() })
            val snapshot = MigrationFileItem(
                file = file,
                relativePath = "Download/source.bin",
                size = file.length(),
                modifiedAt = file.lastModified(),
                category = MigrationCategory.DOWNLOADS
            )
            assertNull(MigrationSourceValidator.problem(snapshot))

            file.appendBytes(byteArrayOf(1, 2, 3))
            assertEquals("旧手机源文件大小已变化", MigrationSourceValidator.problem(snapshot))

            file.delete()
            assertEquals("旧手机源文件已删除", MigrationSourceValidator.problem(snapshot))
        } finally {
            file.delete()
        }
    }

    @Test
    fun appVersionPolicy_skipsSameOrNewerReceiverButKeepsOlderReceiver() {
        val base = AppCompatibilityResult(AppCompatibilityStatus.COMPATIBLE, "兼容")
        val same = MigrationAppVersionPolicy.merge(100L, 100L, base)
        val newer = MigrationAppVersionPolicy.merge(100L, 101L, base)
        val older = MigrationAppVersionPolicy.merge(100L, 99L, base)

        assertTrue(same.alreadyPresent)
        assertTrue(newer.alreadyPresent)
        assertFalse(older.alreadyPresent)
        assertTrue(older.reason.contains("较旧版本"))
    }
}
