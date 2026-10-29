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
patch(gradle, '    testImplementation("junit:junit:4.13.2")', '    testImplementation("junit:junit:4.13.2")\n    testImplementation("org.json:json:20240303")')
network = 'app/src/main/java/com/nuvio/tv/core/di/NetworkModule.kt'
patch(network, '''fun provideAddonApi(retrofit: Retrofit): AddonApi =
        retrofit.create(AddonApi::class.java)''', '''fun provideAddonApi(retrofit: Retrofit, moshi: Moshi, okHttpClient: OkHttpClient): AddonApi =
        com.nuvio.tv.data.cinemana.CinemanaAddonApi(retrofit.create(AddonApi::class.java), moshi, okHttpClient)''')
repo = 'app/src/main/java/com/nuvio/tv/data/repository/AddonRepositoryImpl.kt'
patch(repo, '{ urls, names, enabledStates, _ -> Triple(urls, names, enabledStates) }', '''{ urls, names, enabledStates, _ ->
            Triple((builtinAddonUrls + urls).distinct(), names, enabledStates)
        }''')
patch(repo, '    private val clock: () -> Long\n', '    private val clock: () -> Long,\n    private val builtinAddonUrls: List<String> = emptyList()\n')
patch(repo, '        clock = System::currentTimeMillis\n', '        clock = System::currentTimeMillis,\n        builtinAddonUrls = listOf(com.nuvio.tv.data.cinemana.CinemanaAddonApi.BASE_URL)\n')
stream_contract = 'app/src/main/java/com/nuvio/tv/domain/repository/StreamRepository.kt'
patch(stream_contract, 'forceRefresh: Boolean = false', 'forceRefresh: Boolean = false,\n        parentMetaId: String? = null')
stream_repo = 'app/src/main/java/com/nuvio/tv/data/repository/StreamRepositoryImpl.kt'
patch(stream_repo, '        forceRefresh: Boolean\n    ): Flow<NetworkResult<List<AddonStreams>>> = flow {', '        forceRefresh: Boolean,\n        parentMetaId: String?\n    ): Flow<NetworkResult<List<AddonStreams>>> = flow {')
patch(stream_repo, '            videoId = videoId,\n            season = season,', '            videoId = parentMetaId?.let { "$it|$videoId" } ?: videoId,\n            season = season,')
patch(stream_repo, '                    addons = sourceConfiguration.addons,', '                    parentMetaId = parentMetaId,\n                    addons = sourceConfiguration.addons,')
patch(stream_repo, '        addons: List<Addon>,\n        debridSettings: DebridSettings,', '        parentMetaId: String?,\n        addons: List<Addon>,\n        debridSettings: DebridSettings,')
patch(stream_repo, 'val streamsResult = getStreamsFromAddon(addon, type, videoId)', 'val requestedId = com.nuvio.android.cinemana.CinemanaRequestIdentity.streamId(addon.id, type, videoId, parentMetaId, season, episode)\n                            val streamsResult = getStreamsFromAddon(addon, type, requestedId)')
stream_vm = 'app/src/main/java/com/nuvio/tv/ui/screens/stream/StreamScreenViewModel.kt'
patch(stream_vm, '                streamRepository.getStreamsFromAllAddons(\n                    type = contentType,', '                streamRepository.getStreamsFromAllAddons(\n                    parentMetaId = contentId,\n                    type = contentType,')
player_streams = source / 'app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt'
player_text = player_streams.read_text()
if player_text.count('streamRepository.getStreamsFromAllAddons(') != 3:
    raise SystemExit('Player stream call baseline mismatch')
player_streams.write_text(player_text.replace('streamRepository.getStreamsFromAllAddons(', 'streamRepository.getStreamsFromAllAddons(\n            parentMetaId = contentId,'))
tracking = 'app/src/main/java/com/nuvio/tv/core/tracking/TrackingScrobbleCoordinator.kt'
patch(tracking, 'private val providerRegistry: TrackingProviderRegistry', 'private val providerRegistry: TrackingProviderRegistry,\n    private val bingers: com.nuvio.tv.data.bingers.BingersService')
patch(tracking, '        val scrobblers = providerRegistry.connectedScrobblers()', '        bingers.onPlayback(action, event)\n        val scrobblers = providerRegistry.connectedScrobblers()')
settings = 'app/src/main/java/com/nuvio/tv/ui/screens/settings/TrackingSettingsScreen.kt'
patch(settings, '                    item(key = "tracking_sources") {', '''                    item(key = "bingers") {
                        com.nuvio.tv.data.bingers.BingersSettingsCard()
                    }
                    item(key = "tracking_sources") {''')
patch(settings, '                    item(key = "tracking_sources") {', '''                    item(key = "offline_downloads") {
                        com.nuvio.tv.data.offline.OfflineDownloadsCard()
                    }
                    item(key = "tracking_sources") {''')
stream_screen = 'app/src/main/java/com/nuvio/tv/ui/screens/stream/StreamScreen.kt'
patch(stream_screen, '            Box(modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs)) {', '            Column(modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs)) {')
patch(stream_screen, '''                    }} else null
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamCard(''', '''                    }} else null
                )
                com.nuvio.tv.data.offline.OfflineDownloadButton(stream)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamCard(''')
patch('app/src/main/AndroidManifest.xml', '</application>', '''    <activity android:name="com.nuvio.tv.data.offline.OfflinePlayerActivity"
            android:exported="false" android:screenOrientation="landscape" />
    </application>''')
# Preserve all TV Compose/focus/player code and inject through the existing provider abstraction.
shutil.copytree(root / 'tv-overlay', source, dirs_exist_ok=True)
strings = source / 'app/src/main/res/values/strings.xml'
text = strings.read_text()
import re
text, count = re.subn(r'(<string name="app_name">).*?(</string>)', r'\g<1>NuvioTV-Cinemana\2', text, count=1)
if count != 1:
    raise SystemExit('Missing application name')
strings.write_text(text)
for localized in (source / 'app/src/main/res').glob('values*/*.xml'):
    data = localized.read_text()
    data = re.sub(r'(<string name="app_name">).*?(</string>)', r'\g<1>NuvioTV-Cinemana\2', data)
    localized.write_text(data)
print('TV identity, signing separation and native Cinemana provider applied')

# Public Trakt clients omit the unavailable secret; retain TV device-code UI.
patch('app/src/main/java/com/nuvio/tv/data/repository/TraktAuthService.kt',
      'BuildConfig.TRAKT_CLIENT_ID.isNotBlank() && BuildConfig.TRAKT_CLIENT_SECRET.isNotBlank()',
      'BuildConfig.TRAKT_CLIENT_ID.isNotBlank()')
auth = source / 'app/src/main/java/com/nuvio/tv/data/repository/TraktAuthService.kt'
auth.write_text(auth.read_text().replace('clientSecret = BuildConfig.TRAKT_CLIENT_SECRET',
                                         'clientSecret = BuildConfig.TRAKT_CLIENT_SECRET.takeIf { it.isNotBlank() }'))
dto = source / 'app/src/main/java/com/nuvio/tv/data/remote/dto/trakt/TraktAuthDtos.kt'
data = dto.read_text()
assert data.count('@Json(name = "client_secret") val clientSecret: String') == 3
dto.write_text(data.replace('@Json(name = "client_secret") val clientSecret: String',
                            '@Json(name = "client_secret") val clientSecret: String?'))
for endpoint in ['oauth/device/code', 'oauth/device/token', 'oauth/token', 'oauth/revoke']:
    patch('app/src/main/java/com/nuvio/tv/data/remote/api/TraktApi.kt',
          '@POST("' + endpoint + '")', '@POST("https://auth.trakt.tv/' + endpoint + '")')

# A fork without a configured Nuvio backend must remain usable locally.
patch('app/src/main/java/com/nuvio/tv/MainActivity.kt',
      'hasSeenAuthQrOnFirstLaunch == false &&\\n                        authState !is AuthState.FullAccount &&',
      'hasSeenAuthQrOnFirstLaunch == false &&\\n                        BuildConfig.SUPABASE_URL.isNotBlank() &&\\n                        BuildConfig.SUPABASE_ANON_KEY.isNotBlank() &&\\n                        authState !is AuthState.FullAccount &&')
