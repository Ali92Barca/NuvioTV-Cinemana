# Android TV application identity

Upstream tag: `NuvioMedia/NuvioTV@1.0.0`.

- Android application ID: `com.ali92barca.nuviotv.cinemana`
- Kotlin namespace: retain upstream `com.nuvio.tv` for source compatibility
- Initial fork version name: `1.0.0-cinemana.1`
- In-app updater repository: `Ali92Barca/NuvioTV-Cinemana`
- Release signing: pending, not configured; must use the mobile release keystore through repository-scoped Actions secrets
- Trakt: pending OAuth redirect validation; no credentials stored here

Identity patch is intentionally minimal. Cinemana, offline downloads, Bingers, TV navigation changes and signed APK builds are NOT yet implemented.

The upstream build configuration contains default signing settings. Do not publish any APK without auditing and replacing these with secret-backed release signing and safe debug signing.

