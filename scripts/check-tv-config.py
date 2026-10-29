#!/usr/bin/env python3
"""Static preflight only; Actions separately verifies the produced APK."""
from pathlib import Path
import sys
text = (Path(sys.argv[1]) / 'app/build.gradle.kts').read_text()
debug = text.split('        debug {', 1)[1].split('        release {', 1)[0]
checks = {
    'independent package': 'applicationId = "com.ali92barca.nuviotv.cinemana"' in text,
    'independent debug package': 'variant.applicationId.set("com.ali92barca.nuviotv.cinemana.debug")' in text,
    'independent update repository': 'NuvioTV-Cinemana' in text,
    'debug uses debug certificate': 'signingConfigs.getByName("debug")' in debug and 'signingConfigs.getByName("release")' not in debug,
    'debug is debuggable': 'isDebuggable = true' in debug,
    'release validates credentials': 'if (releaseRequested)' in text and 'debug fallback is prohibited' in text,
    'no inherited keystore or password': '815787' not in text and '../nuviotv.jks' not in text and 'CI_USE_DEBUG_SIGNING' not in text,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ': ' + name)
if not all(checks.values()):
    sys.exit(1)
