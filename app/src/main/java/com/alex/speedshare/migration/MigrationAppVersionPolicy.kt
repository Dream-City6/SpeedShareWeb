package com.alex.speedshare.migration

internal object MigrationAppVersionPolicy {
    fun merge(
        sourceVersionCode: Long,
        receiverVersionCode: Long?,
        base: AppCompatibilityResult
    ): AppCompatibilityResult {
        if (receiverVersionCode == null || sourceVersionCode <= 0L) return base
        return when {
            receiverVersionCode > sourceVersionCode -> AppCompatibilityResult(
                status = AppCompatibilityStatus.COMPATIBLE,
                reason = "新手机已安装更高版本，默认不重复迁移；需要旧 APK 时仍可手动勾选",
                alreadyPresent = true
            )
            receiverVersionCode == sourceVersionCode -> AppCompatibilityResult(
                status = AppCompatibilityStatus.COMPATIBLE,
                reason = "新手机已安装相同版本，默认不重复迁移；仍可手动勾选",
                alreadyPresent = true
            )
            else -> base.copy(reason = "新手机已有较旧版本，可迁移更新。${base.reason}")
        }
    }
}
