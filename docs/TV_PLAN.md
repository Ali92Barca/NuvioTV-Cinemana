# NuvioTV-Cinemana implementation status

Implemented overlay: independent identity, fail-closed release signing, debug signing separation,
native Cinemana AddonApi integration, catalogs/search/metadata/streams/subtitles, quality and
edition handling, and a persistent direct-file offline download prototype with local playback.

CI builds are pinned to official 1.0.0 commit 9f17e8bf4abc799dc8c832d2894a8b3b166e4353.
Cinemana fixture tests passed in Actions; Android compilation/device verification remains separate.

Bingers official API requires Legend/Icon (https://bingers.app/developers).
The experimental mobile session client is preserved for protocol tests, with all runtime
link/send/retry operations disabled. A future supported integration should use device OAuth.

Remaining acceptance: successful current-head APK build/tests; signed release with existing
mobile secrets; on-device Cinemana playback/search/subtitles/remote tests; offline file integrity,
reboot/cancel/storage tests; private-repository updater distribution; Trakt device authorization.

Full evidence and limitations are maintained in TV_VALIDATION.md. No mobile repository modifications.
