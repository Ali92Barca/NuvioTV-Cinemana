"""Decode only the existing mobile key supplied by Actions; never generate a release key."""
import base64
import os
from pathlib import Path
keys = ['MOBILE_KEYSTORE_BASE64', 'NUVIO_RELEASE_STORE_FILE', 'NUVIO_RELEASE_STORE_PASSWORD', 'NUVIO_RELEASE_KEY_ALIAS', 'NUVIO_RELEASE_KEY_PASSWORD']
if any(not os.environ.get(k) for k in keys):
    raise SystemExit('Missing shared mobile signing secrets in the TV repository. No fallback certificate is allowed.')
p = Path(os.environ['NUVIO_RELEASE_STORE_FILE'])
p.touch(mode=0o600, exist_ok=False)
try:
    p.write_bytes(base64.b64decode(os.environ['MOBILE_KEYSTORE_BASE64'], validate=True))
except Exception:
    p.unlink(missing_ok=True)
    raise SystemExit('Invalid signing secret encoding')
