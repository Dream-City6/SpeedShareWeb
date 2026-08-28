package com.alex.speedshare.migration

import android.content.Context
import android.os.Build

enum class AppCompatibilityStatus { COMPATIBLE, REVIEW, INCOMPATIBLE }

data class AppCompatibilityResult(
    val status: AppCompatibilityStatus,
    val reason: String,
    val alreadyPresent: Boolean = false
)

internal object AppCompatibilityAnalyzer {
    private val knownAbis = setOf(
        "arm64-v8a",
        "armeabi-v7a",
        "armeabi",
        "x86",
        "x86_64",
        "mips",
        "mips64"
    )

    fun analyze(context: Context, app: MigrationAppItem, receiver: MigrationPeer?): AppCompatibilityResult {
        if (receiver == null) return AppCompatibilityResult(AppCompatibilityStatus.REVIEW, "尚未连接新手机")
        val packageInfo = runCatching { context.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val applicationInfo = packageInfo?.applicationInfo
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) applicationInfo?.minSdkVersion ?: 0 else 0
        if (receiver.androidSdk > 0 && minSdk > receiver.androidSdk) {
            return AppCompatibilityResult(
                AppCompatibilityStatus.INCOMPATIBLE,
                "需要 Android API $minSdk，新手机是 API ${receiver.androidSdk}"
            )
        }

        val explicitAbis = app.apkFiles.asSequence()
            .flatMap { apk ->
                val normalized = apk.name.replace('_', '-').lowercase()
                knownAbis.asSequence().filter { abi -> normalized.contains(abi) }
            }
            .toSet()

        if (explicitAbis.isNotEmpty() && receiver.supportedAbis.isNotEmpty()) {
            val receiverAbis = receiver.supportedAbis.map { it.lowercase() }.toSet()
            if (explicitAbis.none { it in receiverAbis }) {
                return AppCompatibilityResult(
                    AppCompatibilityStatus.INCOMPATIBLE,
                    "APK 架构 ${explicitAbis.joinToString()} 与新手机 ${receiver.supportedAbis.joinToString()} 不匹配"
                )
            }
            return AppCompatibilityResult(
                AppCompatibilityStatus.COMPATIBLE,
                "Android 版本和已识别 ABI 均匹配"
            )
        }

        if (receiver.androidSdk <= 0 || receiver.supportedAbis.isEmpty()) {
            return AppCompatibilityResult(AppCompatibilityStatus.REVIEW, "新手机兼容信息不完整，安装时由系统最终确认")
        }
        return AppCompatibilityResult(
            AppCompatibilityStatus.REVIEW,
            "未发现明确 ABI split；base APK 内的原生库将在安装时由系统确认"
        )
    }
}
