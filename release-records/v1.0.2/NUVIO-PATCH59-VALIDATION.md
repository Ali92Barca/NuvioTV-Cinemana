## Patch 59 release candidate

Built 7 November 2026 for com.ali92barca.nuviotv, version 1.0.2, version code 6.

The series watch action now reads the originating catalog metadata, tries alternate installed metadata, and falls back to the canonical TMDB episode list. Cee and Cinemana actions retain source episode verification. The fully watched badge updates after the write succeeds.

Validation completed

- Release build succeeded with minification and resource shrinking.
- 30 regression tests passed, zero failures and errors.
- All five unsigned APKs have the expected package and version, and pass 16 KB native library alignment checks.
- User-selected package migration removes the .cinemana suffix.

Signing and device validation

Awaiting local signing by the user with Sign-PATCH59-Release.ps1. Signed release installation, actual Manna'a cross-catalog verification and CBOX speed measurements remain pending.

Release signing and CBOX installation completed

- All five release APK signatures match the expected official certificate.
- Signed ARMv7 release installed successfully on 192.168.10.235:5555.
- Package com.ali92barca.nuviotv, version 1.0.2, version code 6.
- First measured activity launch after installation completed in 1290 ms. This does not measure full home readiness.
- Current-session DPAD test after stopping the background debug app rendered 1260 frames. Median frame time 22 ms, p95 28 ms, p99 36 ms. Android classified 95.63 percent of frames as janky.
- The earlier DPAD sample had p95 93 ms. Page content and cache warm-up differed, so the change cannot be attributed solely to stopping the debug app.
- The current-session profile was not independently confirmed as test.
- The Cee Manna'a search card was observed with a watched check. Cross-catalog mark and unmark behavior still requires verification.
- No source publication was performed.
