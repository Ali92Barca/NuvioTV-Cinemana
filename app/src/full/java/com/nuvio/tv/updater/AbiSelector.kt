package com.nuvio.tv.updater

import android.os.Build
import com.nuvio.tv.data.remote.dto.GitHubAssetDto

internal object AbiSelector {
    private val knownAbis = listOf(
        "arm64-v8a",
        "armeabi-v7a",
        "x86_64",
        "x86"
    )

    fun chooseBestApkAsset(assets: List<GitHubAssetDto>): GitHubAssetDto? =
        chooseBestApkAssetForAbis(assets, Build.SUPPORTED_ABIS?.toList().orEmpty())

    internal fun chooseBestApkAssetForAbis(
        assets: List<GitHubAssetDto>,
        supportedAbis: List<String>
    ): GitHubAssetDto? {
        val apkAssets = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) &&
                !it.name.contains("debug", ignoreCase = true)
        }

        for (abi in supportedAbis) {
            if (abi !in knownAbis) continue
            apkAssets.firstOrNull {
                it.name.contains(abi, ignoreCase = true)
            }?.let { return it }
        }

        return apkAssets.firstOrNull {
            val name = it.name.lowercase()
            knownAbis.none { abi -> name.contains(abi) } &&
                (name.contains("universal") ||
                    Regex("(^|[-_.])all([-_.]|$)").containsMatchIn(name))
        }
    }
}
