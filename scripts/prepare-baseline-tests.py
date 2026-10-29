"""Only make the official baseline runnable without its unavailable release keystore.
No production playback/provider/tracking logic is changed.
"""
from pathlib import Path
import sys
p = Path(sys.argv[1]) / 'app/build.gradle.kts'
s = p.read_text()
old = '        debug {\n            signingConfig = signingConfigs.getByName("release")'
assert s.count(old) == 1
s = s.replace(old, '        debug {\n            signingConfig = signingConfigs.getByName("debug")')
s = s.replace('    testImplementation("junit:junit:4.13.2")', '    testImplementation("junit:junit:4.13.2")\n    testImplementation("org.json:json:20240303")')
p.write_text(s)
