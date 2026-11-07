# Better Stream experimental bridge

Base: 077957d1c24dd073872472829bc484061f6e5b9d on experimental/betterstream-tmdb.

Xtream series search runs even when a direct canonical request returns streams. Arabic/localized and original TMDB titles are searched, and complete bilingual labels can match either title. Stream requests retain the actual native episode ID returned by meta; no Xtream stream ID is synthesized. Conflicting explicit external IDs are rejected.

For non-Arabic works, complete episode number sets must match. Generic playlist titles such as Episode 1 are accepted with that layout; substantive title/date conflicts remain independent. Per the owner's requested exception, a verified TMDB original_language of ar bypasses name/year/count/title/date edition checks after the work identity has been determined. A requested native episode must still exist uniquely.

Numbered separate works (for example Show 2) can map canonical season 1 to playlist season 2 when the playlist uses the base title. This is a general numbered-part rule, not a title-specific exception. The episode ledger supports one playlist containing multiple independent canonical works. Unrelated names without a demonstrated relationship are not automatically grouped.

Search cache: shared in memory, 128 entries, five-minute TTL, subscription/configuration-specific SHA-256 keys. Cached values contain search candidate metadata, never stream URLs or watch progress. This is not a persistent or cross-device cache. Existing stream session caching retains its profile-specific key.

Opening native series metadata also resolves its TMDB identity and learns compatible episodes. The shared episode ledger stores hashed subscription scope, native episode IDs, and public canonical coordinates, without subscription URLs or tokens. Read/write projections preserve explicit profile IDs and remap seasons for separate parts. Unknown episodes and conflicting account identities remain independent. Metadata identity is shared; watch state stays in the existing profile-specific repositories.

Limitations: canonical stream lookup uses cached details and advertised search catalogs. Numbered-part inference requires the source base title and the corresponding native season; arbitrary renamed parts still need an explicit verified relationship. Live search/meta inspection was performed for the reported examples; actual episode content and playback duration still require device testing. No main/upstream changes or release publication are part of this work.

Debug version policy requested by the owner: retain the base version and append Test N (for example 1.0.2 Test 1). Increment experimentalTestNumber in gradle.properties for each new APK delivered for testing. The generated launcher label and versionName both use this number. Test 1 is the first full service-configured Better Stream experimental APK; do not reuse its number for later delivered changes.
