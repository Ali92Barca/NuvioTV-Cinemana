#!/usr/bin/env python3
"""Check TV source for safe signing configuration before release builds."""
from pathlib import Path
import sys

source = Path(sys.argv[1])
gradle = source / "app" / "build.gradle.kts"
data = gradle.read_text(encoding="utf-8")
checks = {
    "distinct application ID": 'applicationId = "com.ali92barca.nuviotv.cinemana"' in data,
    "independent updates": 'NuvioTV-Cinemana' in data,
    "release signing provided by environment": 'NUVIO_RELEASE_STORE_FILE' in data,
}
for name, passed in checks.items():
    print(("PASS" if passed else "FAIL") + ": " + name)
if not all(checks.values()):
    sys.exit(1)
print("NOTE: this is a static preflight, not an APK signature verification.")
