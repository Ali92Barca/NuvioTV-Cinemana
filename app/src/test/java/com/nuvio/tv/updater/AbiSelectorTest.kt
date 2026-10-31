package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbiSelectorTest {

    private fun asset(name: String) = GitHubAssetDto(
        name = name,
        browserDownloadUrl = "https://example.com/$name"
    )

    @Test
    fun `CBOX selects armeabi v7a APK`() {
        val assets = listOf(
            asset("app-arm64-v8a-release.apk"),
            asset("app-armeabi-v7a-release.apk")
        )

        assertEquals(
            "app-armeabi-v7a-release.apk",
            AbiSelector.chooseBestApkAssetForAbis(
                assets, listOf("armeabi-v7a")
            )?.name
        )
    }

    @Test
    fun `universal APK is accepted`() {
        assertEquals(
            "app-universal-release.apk",
            AbiSelector.chooseBestApkAssetForAbis(
                listOf(asset("app-universal-release.apk")),
                listOf("armeabi-v7a")
            )?.name
        )
    }

    @Test
    fun `wrong architecture is rejected`() {
        assertNull(
            AbiSelector.chooseBestApkAssetForAbis(
                listOf(asset("app-arm64-v8a-release.apk")),
                listOf("armeabi-v7a")
            )
        )
    }

    @Test
    fun `debug APK is rejected`() {
        assertNull(
            AbiSelector.chooseBestApkAssetForAbis(
                listOf(asset("app-armeabi-v7a-debug.apk")),
                listOf("armeabi-v7a")
            )
        )
    }

    @Test
    fun `unknown APK name is rejected`() {
        assertNull(
            AbiSelector.chooseBestApkAssetForAbis(
                listOf(asset("mystery-release.apk")),
                listOf("armeabi-v7a")
            )
        )
    }

    @Test
    fun `device ABI preference is respected`() {
        assertEquals(
            "app-arm64-v8a-release.apk",
            AbiSelector.chooseBestApkAssetForAbis(
                listOf(
                    asset("app-armeabi-v7a-release.apk"),
                    asset("app-arm64-v8a-release.apk")
                ),
                listOf("arm64-v8a", "armeabi-v7a")
            )?.name
        )
    }
}
