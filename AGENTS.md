# Experimental Android builds

The owner requires visible sequential debug versions: preserve the base release version and append `Test N`, such as `1.0.2 Test 1`, then `1.0.2 Test 2`.

Before delivering a new changed experimental APK, increment `experimentalTestNumber` in `gradle.properties`. Do not increment for retries of the same undelivered build. Both launcher label and versionName are generated from this number. Never reuse a delivered Test number for changed code or configuration.

Build the full distribution with registered Nuvio, Trakt, SIMKL, and TMDB service configuration. Use the experimental GitHub workflow to inject repository secrets; do not deliver a blank-service diagnostic APK as a functional test build. Keep credentials out of logs and source control.

For title-matcher changes, run `tests/android-probes/ExternalMatcherProbe.java` against the final APK on Android as well as JVM tests. Android ICU rejects some regex properties accepted by desktop Java. Verify a canonical Nuvio stream request on the device before claiming the integration works; startup and unit tests alone do not establish that.

For this Better Stream work, use only `experimental/betterstream-tmdb`, without modifying main or publishing a Release, unless the owner explicitly changes that scope.
