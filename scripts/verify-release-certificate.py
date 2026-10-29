"""Compare release APK signer against the supplied mobile keystore certificate."""
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path
cert = subprocess.run(['keytool', '-exportcert', '-keystore', os.environ['NUVIO_RELEASE_STORE_FILE'], '-alias', os.environ['NUVIO_RELEASE_KEY_ALIAS'], '-storepass:env', 'NUVIO_RELEASE_STORE_PASSWORD'], check=True, capture_output=True).stdout
expected = hashlib.sha256(cert).hexdigest()
signer = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/apksigner'
output = subprocess.run([str(signer), 'verify', '--print-certs', sys.argv[1]], check=True, capture_output=True, text=True).stdout
actual = re.findall(r'certificate SHA-256 digest: ([a-fA-F0-9]+)', output)
if actual != [expected]:
    raise SystemExit('APK signer does not match the provided mobile certificate')
print('Release APK certificate matches supplied mobile signing key')
