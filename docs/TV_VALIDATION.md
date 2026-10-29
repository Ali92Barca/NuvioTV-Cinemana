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
- Gradle test attempt blocked before running: services.gradle.org unreachable in execution environment.
- No local Kotlin compilation, APK or device tests have passed yet. Actions must establish these.

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
- Bingers UI/authentication and player 80% integration; free-account support not verified.
- Offline video/subtitle download queue, persistence and offline playback UI.
- Live Cinemana access from the user's ISP, cross-catalog resolution on device.
- Signed release and release publication; repository is private so unauthenticated updater access
  to its GitHub Releases also requires a distribution decision before claiming updates work.
- Full TV/USB remote tests on Android 9 CBOX and playback tests.

No feature should be described as verified on TV based solely on the mobile contract tests.
