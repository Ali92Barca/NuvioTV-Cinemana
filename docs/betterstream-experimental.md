# Better Stream experimental bridge

Base: 077957d1c24dd073872472829bc484061f6e5b9d on experimental/betterstream-tmdb.

The runtime first requests streams using its existing canonical identity. Empty or HTTP 404 responses can fall back to all advertised searchable catalogs of an installed, enabled external addon. Search row selection rejects explicit IMDb/TMDB conflicts and ambiguous editions. Requests use the selected addon-native movie ID or the exact episode ID returned by meta; no Xtream ID is synthesized.

Series fallback requires a unique season/episode, equal season numbering sets, a matching non-generic episode title, and no conflicting release date or external IDs. This deliberately excludes incomplete metadata. It cannot prove equivalence of different cuts with identical metadata; device playback validation is still required.

Search cache: shared in memory, 128 entries, five-minute TTL, subscription/configuration-specific SHA-256 keys. Cached values contain search candidate metadata, never stream URLs or watch progress. This is not a persistent or cross-device cache. Existing stream session caching retains its profile-specific key.

Canonical playback retains its existing content identity and profile-specific progress. Historical progress or playback launched directly from Xtream catalog identities is not yet projected into SharedPlaybackIdentity; that registry currently accepts Cinemana/CEE only. Do not claim full native Xtream Continue Watching unification.

Limitations: uses already cached canonical details; searches only catalogs explicitly advertising search, without inventing undocumented Better Stream endpoints. Duplicate editions are rejected rather than automatically grouped. No live Better Stream subscription or device playback was exercised. No main/upstream changes or release publication are part of this work.
