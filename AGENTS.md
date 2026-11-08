# Experimental Android builds

The owner requires visible sequential debug versions: preserve the base release version and append `Test N`, such as `1.0.2 Test 1`, then `1.0.2 Test 2`.

Before delivering a new changed experimental APK, increment `experimentalTestNumber` in `gradle.properties`. Do not increment for retries of the same undelivered build. Both launcher label and versionName are generated from this number. Never reuse a delivered Test number for changed code or configuration.

Build the full distribution with registered Nuvio, Trakt, SIMKL, and TMDB service configuration. Use the experimental GitHub workflow to inject repository secrets; do not deliver a blank-service diagnostic APK as a functional test build. Keep credentials out of logs and source control.

For title-matcher changes, run `tests/android-probes/ExternalMatcherProbe.java` against the final APK on Android as well as JVM tests. Android ICU rejects some regex properties accepted by desktop Java. Verify a canonical Nuvio stream request on the device before claiming the integration works; startup and unit tests alone do not establish that.

For this Better Stream work, use only `experimental/betterstream-tmdb`, without modifying main or publishing a Release, unless the owner explicitly changes that scope.

# Matching policies confirmed by the owner

The owner requires reviewing collected issues before new builds; wait for an explicit instruction to build. The owner has now explicitly authorized Test 9 for the Daha 17/Omur shared-history gaps, duplicate Continue Watching card and immediate Search watched badge. Homecoming and Game of Thrones IPTV links were confirmed working by the owner on Test 8; preserve the Test 8 typed movie request fix. Run Android `cmd package compile -m speed -f com.ali92barca.nuviotv.debug` after installation and before final performance comparison/handoff. The owner confirmed this substantially improved Test 7; official 1.0.2 is a release build, so do not present release/debug comparisons as perfectly controlled.

Verified device facts for Test 9: Daha 17 TMDB317883/IMDbtt42764103 has 23 episodes in verified Cinemana/CEE and translated IPTV xt_s_juxzgf_46557; TMDB currently lists25 total/24 aired. Omur TMDB322280/IMDbtt39333720 has6 verified Cinemana episodes and translated IPTV xt_s_juxzgf_49914 / xt_s_juxzgf_50906; TMDB lists7 aired. Reuse the verified same-work provider layout with the matching native episode count, retaining duplicate-coordinate/previous-season contradiction guards. Do not substitute the 115/30-episode alternate cuts. A number at the end of a primary title is not proof of a separate part: check official IMDb-to-TMDB identity before using a global IMDb link for numeric titles; retain separate-part safeguards.

After confirming the work identity, TMDB original_language=ar bypasses edition/count/title/date checks. Keep unique native episode IDs and explicit numbered-part aliases.

For Turkish series (TMDB original_language=tr), matching season episode counts allow direct pairing by sorted episode position, including cumulative source numbering. Do not require equal episode labels, dates, source year or prior numbering to infer the requested season's coordinates. Retain identity-conflict, duplicate-coordinate and contradictory earlier-season-count guards, so a recut/dubbed edition is not accepted because one later season happens to have the same count. Complete and already-aired counts are supported. Turkish translated catalog labels identify relevant catalogs but are not a unique work identity.

The owner verified Violet Night and its separate second-part card on Test 3: links and shared progress work without mixed episodes. Preserve this behavior. Keep watch records separated by profile, original native IDs for playback, and subscription scopes hashed in the shared matching cache. Never clear application data to fix a matcher issue.

Publish streams progressively, including multiple verified results from the same external addon. A slow catalog/edition or the overall search timeout must not discard or delay a verified ready stream. Never issue a canonical Xtream series stream request whose result would be discarded before native episode matching. Movie/direct results and other providers must remain independently visible while optional searches continue.

IPTV BetterPosters must use existing verified matching identities only, retain the old image until the replacement is decoded, reuse existing memory/disk caches, and avoid extra metadata/TMDB matching requests or whole-catalog scans. Limit replacements to visible cards, cancel work on scrolling away, and bound concurrent image loads. Keep profile watch state separate from poster/matching caches.

Test 7 update: the owner approved disabling BetterPosters for IPTV/Xtream cards to reduce navigation load; keep original addon posters. Preserve BetterPosters for Cinemana and CEE. Preserve all playback identity/progress mapping independently of this artwork change. Device ANR traces identify SharedWatchProgressRepository.watchedItems -> SharedPlaybackIdentity.externalRelated/episodeClosure on the main thread: project history off the UI dispatcher, reuse title relationships across episodes, and invalidate cached relationships on matching revisions. Test 7 combines this navigation fix with parallel Cinemana info/link requests. The owner subsequently authorized completing, installing and testing Test 7 on CBOX profile 5, especially shared progress and watched marks.

The owner expanded Test 7 before delivery to include missing IPTV movie progress/watched unification. Keep movie identities separate from TV and subscription scopes hashed; colliding native IDs must not join unrelated works. Movie progress uses verified public IDs while native metadata/playback IDs stay unchanged. Keep additional public-ID stream editions when publishing cached native movie links early. Successful TMDB title information and exact native enrichment can be cached in bounded memory caches; no whole-catalog scans. Complete and test the combined scope before delivering another APK.

For CBOX UI/performance checks requested by the owner, use the existing `test` profile (ID 5), keep account/settings data, and report actual observed cold/warm results and remaining problems. Computer shutdown was explicitly cancelled.
