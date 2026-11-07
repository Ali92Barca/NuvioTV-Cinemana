# NuvioTV v1.0.2, Patch 60

This private source snapshot corresponds to the signed release tested on CBOX 3.0.

The release includes the pre-IPTV download, USB storage, catalog, poster, profile and shared watch identity changes, plus patches 57 through 60. Version is 1.0.2, version code 6. Application ID is com.ali92barca.nuviotv.

The title must first resolve to its correct TMDB identity. Original-language Arabic movies and series use direct identity linking. Arabic series bypass runtime, episode-count and season-layout restrictions. Other series bypass runtime restrictions when total counts match. Source playback IDs remain intact. Previously rejected cached metadata is rechecked.

The final selected regression run passed 52 tests with zero errors and failures. Full release assembly succeeded. All five architectures were signed with the official certificate and passed signature and 16 KB alignment checks. The ARMv7 APK was installed and launched on 192.168.10.235.

The prior release performance sample and its limitations are recorded in the Patch 59 validation document. Functional test feedback remains distinct from automated regression results.

Build using JDK 17, Android SDK 36 and the repository Gradle wrapper. Provide environment-specific settings through local.properties using local.example.properties as a template. The nuvioPrepareUnsignedRelease Gradle property prepares unsigned release APKs for separate local signing.

Public delivery uses the Releases section in Ali92Barca/NuvioTV-Cinemana-Releases. Source and detailed build records use this private repository.
