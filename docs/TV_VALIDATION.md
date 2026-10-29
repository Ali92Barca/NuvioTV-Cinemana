# TV validation and remaining work

## Baseline audit
- Official TV 1.0.0 commit: 9f17e8bf4abc799dc8c832d2894a8b3b166e4353.
- Existing TV repository had identity/preparation scripts only, not a working APK.
- Upstream debug pointed to release signing and disabled debugging. The overlay corrects this.
- Upstream release credential defaults and keystore fallback removed. Release requires all credentials.
- Mobile repository read only at fc3f52a1865df5eb23728c2498e026dd9d701b5e; no changes made there.

## Implemented, awaiting Android build/device validation
- Independent release/debug package IDs, display name and update repository.
- Native provider decorator for all five AddonApi endpoints (manifest/catalog/meta/stream/subtitles).
- Built-in provider inserted into existing addon flow, retaining other installed providers.
- Mobile Cinemana resource/client/matcher/quality/edition/episode mapping code ported unchanged.
- Movies and series catalogs/search, TMDB enrichment and external identity resolution, ordered
  qualities, edition descriptions, edition-bound subtitles are present in the ported adapter.
- Existing TV UI and focus code retained. Remote navigation and playback still need device tests.

## Verification performed locally
- Overlay successfully applied to clean pinned TV checkout.
- Seven static identity/signing assertions passed.
- Four Python signing/service failure-path tests passed.
- Local Gradle attempt blocked before running: services.gradle.org unreachable in execution environment.
- GitHub Actions passed the 43 provider/client fixture tests. TV Kotlin/Hilt compilation and APK assembly passed after fixing the Bingers AuthManager dependency cycle.
- A diagnostic FullDebug APK was assembled on run 40958248986; apksigner verified its v2 signature and aapt confirmed com.ali92barca.nuviotv.cinemana.debug, versionCode 1, versionName 1.0.0-cinemana.1.
- Full TV suite: 1265 tests, 12 failed, 1 skipped. Official baseline: 1263 tests, the identical 12 failures and 1 skip. Two added TV provider tests passed. All six addon-placeholder regressions from the initial integration were fixed by injecting builtin URLs only through production wiring.
- JUnit comparison confirmed zero new/changed failures, zero missing tests and zero new skips. See docs/verification/tv-baseline-comparison.json.
- Full-suite status remains FAILED; inherited failures are not excluded or converted to passing tests. The Release job remains blocked. A diagnostic APK is not a release-ready acceptance claim.
- Run 40958248986 verified the APK but upload was skipped by an implicit success() condition after failed tests. The condition was corrected in 82bf737; a replacement build is in progress.
- No device playback, offline transfer, remote focus or TV Bingers write has been verified.

## Signing and service setup
Release uses MOBILE_KEYSTORE_BASE64, MOBILE_STORE_PASSWORD, MOBILE_KEY_ALIAS and
MOBILE_KEY_PASSWORD in the **TV** repository. Names match the mobile workflow.
GitHub repository secrets are scoped to a repository: referencing these names does NOT copy
mobile secret values. Existing org secrets can instead be granted access to this repository.
Never paste values into source, logs, reports or chat. No API exposed here can copy secret values.

TRAKT_CLIENT_ID, TRAKT_CLIENT_SECRET and TRAKT_REDIRECT_URI must reuse the mobile registration;
TMDB_API_KEY and optionally SIMKL_CLIENT_ID must be supplied to TV Actions as well.
The certificate does not determine Trakt application identity; the OAuth client registration does.
TV device authorization and redirect behavior require validation against that registration.

Debug uses an ordinary development certificate and a separate .debug package, not the mobile
release key. Release verifies its APK certificate against the supplied mobile keystore.
Missing signing or required service values fail release without exposing values.

## Incomplete
- Bingers service/authentication/settings card and STOP-at-80% hook are implemented but await TV compilation/device verification. The official external API requires Legend/Icon. At the user’s explicit request, TV uses the same experimental Scroblarr-compatible authenticated session protocol as mobile, not the official token API. The user confirmed successful watch-history writes on their mobile account on 2026-10-29. TV-specific write behavior remains unverified. Linking validates the session; writes require a separate opt-in. Authentication/subscription failures stop automatic writes. No live Bingers writes have been performed.
- Ported Bingers client tests run in CI. MIT protocol notice retained in SCROBLARR_NOTICE.txt.
- Offline direct-file downloads, sidecar subtitles, persistent DownloadManager IDs, delete/cancel, and local Media3 player implemented but NOT device verified. HLS/DASH downloads are intentionally rejected.
- Downloads are accessible under Settings > Tracking in this initial integration. Offline playback does not currently queue Bingers/Trakt completion.
- Download failure/reboot/full-storage/cancel tests and offline remote navigation still required.
- Live Cinemana access from the user's ISP, cross-catalog resolution on device.
- Signed release and release publication; repository is private so unauthenticated updater access
  to its GitHub Releases also requires a distribution decision before claiming updates work.
- Full TV/USB remote tests on Android 9 CBOX and playback tests.

No feature should be described as verified on TV based solely on the mobile contract tests.

Official Bingers evidence checked 2026-10-29: https://bingers.app/developers and https://bingers.app/integrations. Official device OAuth is a separate paid API path. The selected Scroblarr session path must be tested on a real account; mobile success is user-verified; TV success still needs on-device confirmation.

## Evidence URLs
- Modified source and APK verification: https://github.com/Ali92Barca/NuvioTV-Cinemana/actions/runs/40958248986
- Official runtime baseline: https://github.com/Ali92Barca/NuvioTV-Cinemana/actions/runs/40958156514
- Replacement diagnostic upload run: see the latest tv-build workflow run on commit 82bf737.

The baseline changes only debug signing (to remove the unavailable official keystore dependency)
and adds the same org.json JVM test dependency as the modified build. Its runtime provider,
playback and tracking logic is otherwise unchanged. Both use empty local service properties.
