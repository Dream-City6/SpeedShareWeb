package com.alex.speedshare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageBuilderTest {
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
