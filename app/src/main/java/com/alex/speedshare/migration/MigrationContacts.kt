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
import java.io.BufferedWriter
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
    private data class StructuredName(
        val family: String = "",
        val given: String = "",
        val middle: String = "",
        val prefix: String = "",
        val suffix: String = ""
    )
    private data class OrganizationRecord(
        val company: String,
        val title: String,
        val department: String
    )
    private data class PostalRecord(
        val street: String,
        val city: String,
        val region: String,
        val postcode: String,
        val country: String
    )
    private data class ContactRecord(
        val id: Long,
        var name: String,
        var structuredName: StructuredName? = null,
        val phones: LinkedHashSet<String> = linkedSetOf(),
        val emails: LinkedHashSet<String> = linkedSetOf(),
        val organizations: LinkedHashSet<OrganizationRecord> = linkedSetOf(),
        val addresses: LinkedHashSet<PostalRecord> = linkedSetOf(),
        val birthdays: LinkedHashSet<String> = linkedSetOf(),
        val notes: LinkedHashSet<String> = linkedSetOf(),
        val websites: LinkedHashSet<String> = linkedSetOf(),
        val nicknames: LinkedHashSet<String> = linkedSetOf()
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

        val dataProjection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7,
            ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9,
            ContactsContract.Data.DATA10
        )
        val richMimeTypes = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE
        )
        val placeholders = richMimeTypes.joinToString(",") { "?" }
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            dataProjection,
            "${ContactsContract.Data.MIMETYPE} IN ($placeholders)",
            richMimeTypes,
            null
        )?.use { cursor ->
            val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val mimeIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            fun string(column: String): String {
                val index = cursor.getColumnIndex(column)
                return if (index >= 0) cursor.getString(index).orEmpty().trim() else ""
            }
            while (cursor.moveToNext()) {
                val contact = contacts[cursor.getLong(contactIdIndex)] ?: continue
                when (cursor.getString(mimeIndex)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        contact.structuredName = StructuredName(
                            family = string(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME),
                            given = string(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME),
                            middle = string(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME),
                            prefix = string(ContactsContract.CommonDataKinds.StructuredName.PREFIX),
                            suffix = string(ContactsContract.CommonDataKinds.StructuredName.SUFFIX)
                        )
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        val company = string(ContactsContract.CommonDataKinds.Organization.COMPANY)
                        val title = string(ContactsContract.CommonDataKinds.Organization.TITLE)
                        val department = string(ContactsContract.CommonDataKinds.Organization.DEPARTMENT)
                        if (company.isNotBlank() || title.isNotBlank() || department.isNotBlank()) {
                            contact.organizations += OrganizationRecord(company, title, department)
                        }
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val postal = PostalRecord(
                            street = string(ContactsContract.CommonDataKinds.StructuredPostal.STREET),
                            city = string(ContactsContract.CommonDataKinds.StructuredPostal.CITY),
                            region = string(ContactsContract.CommonDataKinds.StructuredPostal.REGION),
                            postcode = string(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE),
                            country = string(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY)
                        )
                        if (listOf(postal.street, postal.city, postal.region, postal.postcode, postal.country).any { it.isNotBlank() }) {
                            contact.addresses += postal
                        }
                    }
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        val type = string(ContactsContract.CommonDataKinds.Event.TYPE).toIntOrNull()
                        val value = string(ContactsContract.CommonDataKinds.Event.START_DATE)
                        if (type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY && value.isNotBlank()) {
                            contact.birthdays += value
                        }
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        string(ContactsContract.CommonDataKinds.Note.NOTE).takeIf { it.isNotBlank() }?.let(contact.notes::add)
                    }
                    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE -> {
                        string(ContactsContract.CommonDataKinds.Website.URL).takeIf { it.isNotBlank() }?.let(contact.websites::add)
                    }
                    ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> {
                        string(ContactsContract.CommonDataKinds.Nickname.NAME).takeIf { it.isNotBlank() }?.let(contact.nicknames::add)
                    }
                }
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
                appendVCardLine(writer, "BEGIN:VCARD")
                appendVCardLine(writer, "VERSION:3.0")
                appendVCardLine(writer, "FN:${escapeVCard(contact.name)}")
                contact.structuredName?.let { name ->
                    if (listOf(name.family, name.given, name.middle, name.prefix, name.suffix).any { it.isNotBlank() }) {
                        appendVCardLine(
                            writer,
                            "N:${escapeVCard(name.family)};${escapeVCard(name.given)};${escapeVCard(name.middle)};${escapeVCard(name.prefix)};${escapeVCard(name.suffix)}"
                        )
                    }
                }
                contact.nicknames.forEach { appendVCardLine(writer, "NICKNAME:${escapeVCard(it)}") }
                contact.phones.forEach { appendVCardLine(writer, "TEL:${escapeVCard(it)}") }
                contact.emails.forEach { appendVCardLine(writer, "EMAIL:${escapeVCard(it)}") }
                contact.organizations.forEach { org ->
                    if (org.company.isNotBlank() || org.department.isNotBlank()) {
                        appendVCardLine(writer, "ORG:${escapeVCard(org.company)};${escapeVCard(org.department)}")
                    }
                    if (org.title.isNotBlank()) appendVCardLine(writer, "TITLE:${escapeVCard(org.title)}")
                }
                contact.addresses.forEach { address ->
                    appendVCardLine(
                        writer,
                        "ADR:;;${escapeVCard(address.street)};${escapeVCard(address.city)};${escapeVCard(address.region)};${escapeVCard(address.postcode)};${escapeVCard(address.country)}"
                    )
                }
                contact.birthdays.firstOrNull()?.let { appendVCardLine(writer, "BDAY:${escapeVCard(it)}") }
                contact.websites.forEach { appendVCardLine(writer, "URL:${escapeVCard(it)}") }
                contact.notes.forEach { appendVCardLine(writer, "NOTE:${escapeVCard(it)}") }
                appendVCardLine(writer, "END:VCARD")
            }
        }
        return PreparedContacts(file, contacts.size)
    }

    private fun appendVCardLine(writer: BufferedWriter, line: String) {
        if (line.length <= 72) {
            writer.appendLine(line)
            return
        }
        var offset = 0
        var first = true
        while (offset < line.length) {
            val end = (offset + if (first) 72 else 71).coerceAtMost(line.length)
            if (!first) writer.append(' ')
            writer.append(line, offset, end)
            writer.newLine()
            offset = end
            first = false
        }
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
                    "姓名、电话、邮箱、结构化姓名、公司/职位/部门、地址、生日、备注、网站和昵称会写入 VCF。SpeedShare 不会直接修改新手机通讯录；到达后由系统联系人应用完成最终导入。",
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
