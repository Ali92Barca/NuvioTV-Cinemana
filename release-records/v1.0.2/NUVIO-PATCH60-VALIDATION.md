## Patch 60

Requested policy

- Resolve the correct TMDB title first.
- Original-language Arabic movies and series use direct identity linking. Arabic series bypass duration, episode-count and season-layout matching restrictions.
- Non-Arabic series bypass duration matching when total episode counts match.
- Other editions retain the previous matching policy.

Implementation

- Shared matching logic is used by Cinemana and Cee.
- Native playback IDs are preserved when season coordinates are projected.
- Canonical Cee stream lookup uses the same coordinates as the metadata projection.
- Cached rejected series details are rechecked without deleting watch data or cache files.

Source checks

- Manna'a has TMDB original_language ar and 15 Cee episodes.
- Noon Al Niswa 2023 has TMDB original_language ar and 30 Cee episodes.
- Hercai has TMDB original_language tr and 53 Cee episodes against 69 official episodes.

Build and device validation

The final release build succeeded in 14m 33s. All 52 selected regression tests passed with zero failures and errors. All five unsigned APKs have the expected package and version and pass 16 KB alignment verification. All five signed release APKs match the official certificate. The ARMv7 release installed successfully as an update on 192.168.10.235:5555 and launched successfully. Activity launch took 1550 ms, which is not a full-home readiness measurement. Functional cross-catalog mark/unmark verification remains for the user.


