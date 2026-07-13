package com.alex.speedshare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageBuilderTest {
    @Test
    fun directoryPageShowsStorageAndChecksCapacityBeforeUploading() {
        val html = directoryPage(remoteManagementEnabled = true)

        assertTrue(html.contains("id=\"liveStorage\""))
        assertTrue(html.contains("data.uploadAvailableBytes"))
        assertTrue(html.contains("totalBytes > latestUploadAvailableBytes"))
        assertTrue(html.contains("web_upload_space_insufficient"))
    }

    @Test
    fun liveRefreshDoesNotInterruptAnActiveUploadQueue() {
        val html = directoryPage(remoteManagementEnabled = true)

        assertTrue(html.contains("if(uploadInProgress){contentChangePending=true;return;}"))
        assertTrue(html.contains("failedUploadEntries.length === 0"))
    }

    private val file = WebItem(
        name = "example.txt",
        isDirectory = false,
        mimeType = "text/plain",
        size = 128L,
        modifiedAt = 1_700_000_000_000L,
        openUrl = "/view?path=example.txt",
        previewUrl = "/view?path=example.txt",
        downloadUrl = "/download?path=example.txt",
        thumbnailUrl = null,
        displayPath = "/example.txt",
        relativePath = "example.txt",
        previewKind = PreviewKind.DOWNLOAD
    )
    private val folder = WebItem(
        name = "Documents",
        isDirectory = true,
        mimeType = "inode/directory",
        size = 0L,
        modifiedAt = 1_700_000_000_000L,
        openUrl = "/?path=Documents",
        previewUrl = null,
        downloadUrl = null,
        thumbnailUrl = null,
        displayPath = "/Documents",
        relativePath = "Documents",
        previewKind = PreviewKind.DOWNLOAD
    )

    @Test
    fun managedDirectoryIncludesCompactContextMenuAndKeyboardSupport() {
        val html = directoryPage(remoteManagementEnabled = true)

        assertTrue(html.contains("id=\"contextMenu\""))
        assertTrue(html.contains("runContextAction('copy-link')"))
        assertFalse(html.contains("runContextAction('delete')"))
        assertTrue(html.contains("<svg viewBox=\"0 0 24 24\""))
        assertTrue(html.contains("oncontextmenu=\"openContextMenu(event,this)\""))
        assertTrue(html.contains("tabindex=\"0\""))
        assertTrue(html.contains("resetWebSettings()"))
        assertTrue(html.contains("removeUploadEntry(index)"))
        assertTrue(html.contains("handleFolderClick(event,this)"))
    }

    @Test
    fun readOnlyDirectoryKeepsTheNativeBrowserContextMenu() {
        val html = directoryPage(remoteManagementEnabled = false)

        assertFalse(html.contains("id=\"contextMenu\""))
        assertFalse(html.contains("oncontextmenu=\"openContextMenu(event,this)\""))
    }

    @Test
    fun emptySelectedDownloadPageStillContainsTheSettingsModal() {
        val html = WebPageBuilder.buildSelectedPage(
            items = emptyList(),
            language = ResolvedLanguage.ENGLISH,
            clipboardSyncEnabled = false,
            pageVersion = "test"
        )

        assertTrue(html.contains("id=\"managerModal\""))
        assertTrue(html.contains("openWebSettings()"))
    }

    @Test
    fun protectedPageShowsSignOutButUnprotectedPageDoesNot() {
        val protectedHtml = WebPageBuilder.buildSelectedPage(
            items = listOf(file),
            language = ResolvedLanguage.ENGLISH,
            clipboardSyncEnabled = false,
            pageVersion = "test",
            accessProtected = true
        )
        val unprotectedHtml = WebPageBuilder.buildSelectedPage(
            items = listOf(file),
            language = ResolvedLanguage.ENGLISH,
            clipboardSyncEnabled = false,
            pageVersion = "test"
        )

        assertTrue(protectedHtml.contains("action=\"/logout\""))
        assertTrue(protectedHtml.contains("Sign out"))
        assertFalse(unprotectedHtml.contains("action=\"/logout\""))
    }

    @Test
    fun loginPageUsesOnlyAPasswordAndPreservesTheDestination() {
        val html = WebPageBuilder.buildLoginPage(
            language = ResolvedLanguage.ENGLISH,
            next = "/download?path=My File.txt",
            invalidPassword = true
        )

        assertTrue(html.contains("name=\"password\""))
        assertFalse(html.contains("name=\"username\""))
        assertTrue(html.contains("No username is required"))
        assertTrue(html.contains("The password is incorrect"))
        assertTrue(html.contains("/login?next=%2Fdownload%3Fpath%3DMy%20File.txt"))
    }

    private fun directoryPage(remoteManagementEnabled: Boolean): String =
        WebPageBuilder.buildDirectoryPage(
            displayPath = "/storage/emulated/0",
            relativePath = "",
            items = listOf(folder, file),
            uploadEnabled = true,
            remoteManagementEnabled = remoteManagementEnabled,
            clipboardSyncEnabled = false,
            deleteToTrashByDefault = true,
            language = ResolvedLanguage.ENGLISH,
            pageVersion = "test"
        )
}
