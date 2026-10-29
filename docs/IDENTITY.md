# Android TV identity and signing

Official baseline: NuvioMedia/NuvioTV 1.0.0 (9f17e8bf4abc799dc8c832d2894a8b3b166e4353).

- Release package: `com.ali92barca.nuviotv.cinemana`
- Debug package: `com.ali92barca.nuviotv.cinemana.debug`
- Namespace remains `com.nuvio.tv` for source compatibility.
- Version: `1.0.0-cinemana.1`, fork versionCode 1.
- Updater repository: `Ali92Barca/NuvioTV-Cinemana`.
- Release uses the existing mobile keystore through TV repository Actions secrets.
- Debug uses a development key; it never requires the production keystore.
- Trakt must reuse the mobile client registration; certificate identity alone is unrelated to Trakt's application limit.

See TV_VALIDATION.md for verification status and missing service/signing dependencies.
