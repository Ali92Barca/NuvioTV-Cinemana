# NuvioTV-Cinemana implementation plan

## Baseline
- Upstream: https://github.com/NuvioMedia/NuvioTV
- Baseline tag: 1.0.0
- Framework: Kotlin, Compose TV, Media3
- Status: source preparation only; no TV application code changes or APK build yet.

## Planned work
1. Inspect official 1.0.0 build variants, package IDs, deep links and remote focus navigation.
2. Add independent application ID and branding without replacing the official installation.
3. Port native Cinemana catalogs, movie/series search, metadata, TMDB/IMDb matching, episodes, subtitles and source ranking.
4. Add translated/dubbed source labeling and playback descriptions.
5. Implement opt-in Bingers integration and 80% progress reporting, subject to API/free-account verification.
6. Implement offline downloads and subtitle storage, with clear storage and content-permission handling.
7. Add TV-specific remote/D-pad testing and device acceptance.
8. Set up separate debug and signed release workflows.

## Security and identity
- Never commit signing keys, passwords, access tokens or OAuth client secrets.
- GitHub Actions secrets are repository-scoped; configure signing secrets for this repository separately.
- Use the same signing key as mobile for release, but a distinct Android application ID.
- Reuse Trakt OAuth application configuration only after verifying redirect/deep-link compatibility.
- Same Android signing certificate alone does not unify Trakt application identity.

## Build policy
- Do not label any APK release-ready without successful compilation, playback tests and remote-control acceptance.
- Preserve upstream GPL-3.0 notices and license when distributing derivatives.

