# Experimental Android builds

The owner requires visible sequential debug versions: preserve the base release version and append `Test N`, such as `1.0.2 Test 1`, then `1.0.2 Test 2`.

Before delivering a new changed experimental APK, increment `experimentalTestNumber` in `gradle.properties`. Do not increment for retries of the same undelivered build. Both launcher label and versionName are generated from this number. Never reuse a delivered Test number for changed code or configuration.

Build the full distribution with registered Nuvio, Trakt, SIMKL, and TMDB service configuration. Use the experimental GitHub workflow to inject repository secrets; do not deliver a blank-service diagnostic APK as a functional test build. Keep credentials out of logs and source control.

For title-matcher changes, run `tests/android-probes/ExternalMatcherProbe.java` against the final APK on Android as well as JVM tests. Android ICU rejects some regex properties accepted by desktop Java. Verify a canonical Nuvio stream request on the device before claiming the integration works; startup and unit tests alone do not establish that.

For this Better Stream work, use only `experimental/betterstream-tmdb`, without modifying main or publishing a Release, unless the owner explicitly changes that scope.

# Matching policies confirmed by the owner

After confirming the work identity, TMDB original_language=ar bypasses edition/count/title/date checks. Keep unique native episode IDs and explicit numbered-part aliases.

For Turkish series (TMDB original_language=tr), matching season episode counts allow direct pairing by sorted episode position, including cumulative source numbering. Do not require equal episode labels, dates, source year or prior numbering to infer the requested season's coordinates. Retain identity-conflict, duplicate-coordinate and contradictory earlier-season-count guards, so a recut/dubbed edition is not accepted because one later season happens to have the same count. Complete and already-aired counts are supported. Turkish translated catalog labels identify relevant catalogs but are not a unique work identity.

The owner verified Violet Night and its separate second-part card on Test 3: links and shared progress work without mixed episodes. Preserve this behavior. Keep watch records separated by profile, original native IDs for playback, and subscription scopes hashed in the shared matching cache. Never clear application data to fix a matcher issue.

Publish streams progressively, including multiple verified results from the same external addon. A slow catalog/edition or the overall search timeout must not discard or delay a verified ready stream. Never issue a canonical Xtream series stream request whose result would be discarded before native episode matching. Movie/direct results and other providers must remain independently visible while optional searches continue.

IPTV BetterPosters must use existing verified matching identities only, retain the old image until the replacement is decoded, reuse existing memory/disk caches, and avoid extra metadata/TMDB matching requests or whole-catalog scans. Limit replacements to visible cards, cancel work on scrolling away, and bound concurrent image loads. Keep profile watch state separate from poster/matching caches.

Test 7 update: the owner approved disabling BetterPosters for IPTV/Xtream cards to reduce navigation load; keep original addon posters. Preserve BetterPosters for Cinemana and CEE. Preserve all playback identity/progress mapping independently of this artwork change. Device ANR traces identify SharedWatchProgressRepository.watchedItems -> SharedPlaybackIdentity.externalRelated/episodeClosure on the main thread: project history off the UI dispatcher, reuse title relationships across episodes, and invalidate cached relationships on matching revisions. Test 7 combines this navigation fix with parallel Cinemana info/link requests. The owner subsequently authorized completing, installing and testing Test 7 on CBOX profile 5, especially shared progress and watched marks.

For CBOX UI/performance checks requested by the owner, use the existing `test` profile (ID 5), keep account/settings data, and report actual observed cold/warm results and remaining problems. Computer shutdown was explicitly cancelled.
