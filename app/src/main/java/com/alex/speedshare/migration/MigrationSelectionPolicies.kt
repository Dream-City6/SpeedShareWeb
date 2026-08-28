package com.alex.speedshare.migration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MigrationDuplicatePolicy {
    SKIP_IDENTICAL_KEEP_CONFLICT,
    OVERWRITE,
    KEEP_BOTH
}

enum class MigrationSelectionPreset {
    ALL,
    RECOMMENDED,
    CUSTOM
}

internal object MigrationDuplicatePolicyRegistry {
    private val _current = MutableStateFlow(MigrationDuplicatePolicy.SKIP_IDENTICAL_KEEP_CONFLICT)
    val current = _current.asStateFlow()

    fun set(policy: MigrationDuplicatePolicy) {
        _current.value = policy
    }
}

internal object MigrationFileSelectionRegistry {
    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths = _selectedPaths.asStateFlow()
    private var catalog: Set<String>? = null
    private var lastSource: List<MigrationFileItem>? = null

    @Synchronized
    fun sync(files: List<MigrationFileItem>) {
        if (lastSource === files) return
        lastSource = files
        val selectable = files.asSequence()
            .filter { it.category in FILE_CATEGORIES }
            .mapTo(linkedSetOf()) { it.relativePath }
        if (catalog != selectable) {
            catalog = selectable
            _selectedPaths.value = selectable
        }
    }

    fun toggle(path: String) {
        val next = _selectedPaths.value.toMutableSet()
        if (!next.add(path)) next.remove(path)
        _selectedPaths.value = next
    }

    fun select(paths: Set<String>, selected: Boolean) {
        _selectedPaths.value = if (selected) _selectedPaths.value + paths else _selectedPaths.value - paths
    }

    fun selectAll() {
        _selectedPaths.value = catalog.orEmpty()
    }

    fun selectNone() {
        _selectedPaths.value = emptySet()
    }

    fun filterTransferItems(items: List<MigrationFileItem>): List<MigrationFileItem> {
        val known = catalog ?: return items
        val selected = _selectedPaths.value
        return items.filter { item ->
            item.category !in FILE_CATEGORIES || item.relativePath !in known || item.relativePath in selected
        }
    }

    val fileCategories: Set<MigrationCategory> get() = FILE_CATEGORIES

    private val FILE_CATEGORIES = setOf(
        MigrationCategory.DOCUMENTS,
        MigrationCategory.DOWNLOADS,
        MigrationCategory.OTHER
    )
}

data class MigrationSelectionSummary(
    val items: List<MigrationFileItem>,
    val totalBytes: Long,
    val totalItems: Int,
    val appCount: Int,
    val photoCount: Int,
    val videoCount: Int
)

internal object MigrationSelectionCalculator {
    fun effectiveItems(
        scanResult: MigrationScanResult,
        selectedCategories: Set<MigrationCategory>
    ): MigrationSelectionSummary {
        val normal = scanResult.files.filter { it.category in selectedCategories }
        val mediaFiltered = MigrationMediaSelectionRegistry.filterTransferItems(normal)
        val fileFiltered = MigrationFileSelectionRegistry.filterTransferItems(mediaFiltered)
        val apps = if (MigrationCategory.APPS in selectedCategories) {
            MigrationScannerV2.appTransferItems(scanResult.apps)
        } else {
            emptyList()
        }
        val items = fileFiltered + apps
        val selectedPackages = MigrationAppSelectionRegistry.selectedPackages.value
        return MigrationSelectionSummary(
            items = items,
            totalBytes = items.sumOf { it.size },
            totalItems = items.size,
            appCount = if (MigrationCategory.APPS in selectedCategories) selectedPackages.size else 0,
            photoCount = fileFiltered.count { it.category == MigrationCategory.PHOTOS },
            videoCount = fileFiltered.count { it.category == MigrationCategory.VIDEOS }
        )
    }

    fun presetCategories(preset: MigrationSelectionPreset): Set<MigrationCategory> = when (preset) {
        MigrationSelectionPreset.ALL -> MigrationCategory.entries.toSet()
        MigrationSelectionPreset.RECOMMENDED -> setOf(
            MigrationCategory.PHOTOS,
            MigrationCategory.VIDEOS,
            MigrationCategory.DOCUMENTS,
            MigrationCategory.DOWNLOADS,
            MigrationCategory.APPS
        )
        MigrationSelectionPreset.CUSTOM -> emptySet()
    }
}
