package com.alex.speedshare.migration

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.alex.speedshare.AppSettings
import com.alex.speedshare.ui.theme.SpeedShareTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object MigrationContactsRegistry {
    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count = _count.asStateFlow()
    private val _preparing = MutableStateFlow(false)
    val preparing = _preparing.asStateFlow()
    @Volatile private var preparedFile: File? = null

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }

    suspend fun prepare(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) {
            _enabled.value = false
            return@withContext Result.failure(SecurityException("contacts_permission_required"))
        }
        _preparing.value = true
        try {
            val result = runCatching { exportContacts(context.applicationContext) }
            result.onSuccess { prepared ->
                preparedFile = prepared.file
                _count.value = prepared.count
                _enabled.value = prepared.count > 0
            }
            result
                .map { it.count }
                .also { if (it.isFailure) _enabled.value = false }
        } finally {
            _preparing.value = false
        }
    }

    fun preparedItem(): MigrationFileItem? {
        if (!_enabled.value) return null
        val file = preparedFile?.takeIf { it.isFile && it.canRead() } ?: return null
        return MigrationFileItem(
            file = file,
            relativePath = "Download/SpeedShareWeb/Contacts/${file.name}",
            size = file.length(),
            modifiedAt = file.lastModified(),
            category = MigrationCategory.DOCUMENTS
        )
    }

    private data class PreparedContacts(val file: File, val count: Int)
    private data class ContactRecord(
        val id: Long,
        var name: String,
        val phones: LinkedHashSet<String> = linkedSetOf(),
        val emails: LinkedHashSet<String> = linkedSetOf()
    )

    private fun exportContacts(context: Context): PreparedContacts {
        val resolver = context.contentResolver
        val contacts = linkedMapOf<Long, ContactRecord>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null,
            null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex).orEmpty().ifBlank { "未命名联系人" }
                contacts[id] = ContactRecord(id, name)
            }
        }

        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val valueIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val value = cursor.getString(valueIndex).orEmpty().trim()
                if (value.isNotBlank()) contacts[cursor.getLong(idIndex)]?.phones?.add(value)
            }
        }

        resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
            val valueIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (cursor.moveToNext()) {
                val value = cursor.getString(valueIndex).orEmpty().trim()
                if (value.isNotBlank()) contacts[cursor.getLong(idIndex)]?.emails?.add(value)
            }
        }

        val root = File(context.filesDir, "migration_generated").apply { mkdirs() }
        root.listFiles()?.filter { it.name.startsWith("contacts-") && it.extension == "vcf" }?.forEach { old ->
            if (System.currentTimeMillis() - old.lastModified() > 24L * 60L * 60L * 1000L) old.delete()
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(root, "contacts-$stamp.vcf")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            contacts.values.forEach { contact ->
                writer.appendLine("BEGIN:VCARD")
                writer.appendLine("VERSION:3.0")
                writer.appendLine("FN:${escapeVCard(contact.name)}")
                contact.phones.forEach { writer.appendLine("TEL:${escapeVCard(it)}") }
                contact.emails.forEach { writer.appendLine("EMAIL:${escapeVCard(it)}") }
                writer.appendLine("END:VCARD")
            }
        }
        return PreparedContacts(file, contacts.size)
    }

    private fun escapeVCard(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace(";", "\\;")
        .replace(",", "\\,")
}

class MigrationContactsSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) {
                MigrationContactsSelectionScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun MigrationContactsSelectionScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by MigrationContactsRegistry.enabled.collectAsState()
    val count by MigrationContactsRegistry.count.collectAsState()
    val preparing by MigrationContactsRegistry.preparing.collectAsState()
    var permissionGranted by remember { mutableStateOf(MigrationContactsRegistry.hasPermission(context)) }
    var error by remember { mutableStateOf<String?>(null) }

    fun prepareContacts() {
        scope.launch {
            error = null
            val result = MigrationContactsRegistry.prepare(context)
            result.exceptionOrNull()?.let { error = "读取联系人失败：${it.message.orEmpty()}" }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted || MigrationContactsRegistry.hasPermission(context)
        if (permissionGranted) prepareContacts()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("联系人", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("导出为标准 VCF，传到新手机后交给系统联系人应用导入。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onClose) { Text("完成") }
            }

            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = enabled,
                            enabled = permissionGranted && !preparing && count > 0,
                            onCheckedChange = MigrationContactsRegistry::setEnabled
                        )
                        Column(Modifier.weight(1f)) {
                            Text(if (permissionGranted) "迁移联系人" else "需要读取联系人权限", fontWeight = FontWeight.Bold)
                            Text(
                                if (count > 0) "已准备 $count 个联系人" else "只在你进入此功能时申请权限，不影响其他换机内容。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (preparing) CircularProgressIndicator(Modifier.padding(6.dp))
                    }
                    if (!permissionGranted) {
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("允许读取联系人") }
                    } else {
                        OutlinedButton(onClick = ::prepareContacts, enabled = !preparing, modifier = Modifier.fillMaxWidth()) {
                            Text("重新读取联系人")
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            Card(shape = RoundedCornerShape(18.dp)) {
                Text(
                    "SpeedShare 不会直接修改新手机通讯录。VCF 到达新手机后，你可以在换机报告中点“导入联系人”，由系统联系人应用完成最终导入。",
                    Modifier.padding(15.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

internal object MigrationContactsImporter {
    fun findLatestReceived(): File? {
        val dir = MigrationStorageLayout.contactsDir()
        return dir.listFiles()?.filter { it.isFile && it.extension.equals("vcf", true) }?.maxByOrNull { it.lastModified() }
    }

    fun openLatest(context: Context): Boolean {
        val file = findLatestReceived() ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/x-vcard")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
