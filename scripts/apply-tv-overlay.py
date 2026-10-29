#!/usr/bin/env python3
"""Fail-closed overlay for the pinned official TV baseline."""
from pathlib import Path
import shutil
import sys

root = Path(__file__).resolve().parents[1]
source = Path(sys.argv[1]).resolve()

def patch(path, old, new):
    target = source / path
    text = target.read_text()
    if text.count(old) != 1:
        raise SystemExit(f'Baseline mismatch: {path}')
    target.write_text(text.replace(old, new, 1))

gradle = 'app/build.gradle.kts'
patch(gradle, 'applicationId = "com.nuvio.tv"', 'applicationId = "com.ali92barca.nuviotv.cinemana"')
patch(gradle, 'versionCode = 1062', 'versionCode = 1')
patch(gradle, 'versionName = "1.0.0"', 'versionName = "1.0.0-cinemana.1"')
for key, old, new in [('GITHUB_OWNER', 'NuvioMedia', 'Ali92Barca'), ('GITHUB_REPO', 'NuvioTV', 'NuvioTV-Cinemana')]:
    patch(gradle, f'buildConfigField("String", "{key}", "\\"{old}\\"")', f'buildConfigField("String", "{key}", "\\"{new}\\"")')
patch(gradle, 'val useDebugReleaseSigning = env("CI_USE_DEBUG_SIGNING").equals("true", ignoreCase = true)', 'val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }')
for key, fallback in [('KEY_ALIAS', 'nuviotv'), ('KEY_PASSWORD', '815787'), ('STORE_PASSWORD', '815787')]:
    patch(gradle, f'localProperties.getProperty("NUVIO_RELEASE_{key}", "{fallback}")', f'localProperties.getProperty("NUVIO_RELEASE_{key}")')
patch(gradle, 'android {\n', '''if (releaseRequested) {
    require(listOf(releaseStoreFilePath, releaseKeyAliasValue, releaseKeyPasswordValue, releaseStorePasswordValue).all { !it.isNullOrBlank() }) {
        "Release requires all four NUVIO_RELEASE signing values; debug fallback is prohibited"
    }
    require(File(releaseStoreFilePath!!).isFile) { "Release keystore is unavailable" }
}

android {
''')
patch(gradle, 'storeFile = releaseStoreFilePath?.let(::file) ?: file("../nuviotv.jks")', 'storeFile = releaseStoreFilePath?.let(::file)')
patch(gradle, '''        debug {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false''', '''        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true''')
patch(gradle, '''signingConfig = if (useDebugReleaseSigning) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }''', 'signingConfig = signingConfigs.getByName("release")')
patch(gradle, 'variant.applicationId.set(if (isPlaystore) "com.nuvio.appdebug" else "com.nuviodebug.com")', 'variant.applicationId.set("com.ali92barca.nuviotv.cinemana.debug")')
network = 'app/src/main/java/com/nuvio/tv/core/di/NetworkModule.kt'
patch(network, '''fun provideAddonApi(retrofit: Retrofit): AddonApi =
        retrofit.create(AddonApi::class.java)''', '''fun provideAddonApi(retrofit: Retrofit, moshi: Moshi, okHttpClient: OkHttpClient): AddonApi =
        com.nuvio.tv.data.cinemana.CinemanaAddonApi(retrofit.create(AddonApi::class.java), moshi, okHttpClient)''')
repo = 'app/src/main/java/com/nuvio/tv/data/repository/AddonRepositoryImpl.kt'
patch(repo, '{ urls, names, enabledStates, _ -> Triple(urls, names, enabledStates) }', '''{ urls, names, enabledStates, _ ->
            Triple((listOf(com.nuvio.tv.data.cinemana.CinemanaAddonApi.BASE_URL) + urls).distinct(), names, enabledStates)
        }''')
# Preserve all TV Compose/focus/player code and inject through the existing provider abstraction.
shutil.copytree(root / 'tv-overlay', source, dirs_exist_ok=True)
strings = source / 'app/src/main/res/values/strings.xml'
text = strings.read_text()
import re
text, count = re.subn(r'(<string name="app_name">).*?(</string>)', r'\g<1>NuvioTV-Cinemana\2', text, count=1)
if count != 1:
    raise SystemExit('Missing application name')
strings.write_text(text)
print('TV identity, signing separation and native Cinemana provider applied')
