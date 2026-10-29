import base64
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class SigningTests(unittest.TestCase):
    def test_missing_credentials_creates_no_key(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / 'release.jks'
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/prepare-signing.py')], env={'PATH': os.environ['PATH'], 'NUVIO_RELEASE_STORE_FILE': str(target)}, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(target.exists())

    def test_decode_is_private_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / 'release.jks'
            env = dict(PATH=os.environ['PATH'], MOBILE_KEYSTORE_BASE64=base64.b64encode(b'fixture bytes').decode(), NUVIO_RELEASE_STORE_FILE=str(target), NUVIO_RELEASE_STORE_PASSWORD='fixture', NUVIO_RELEASE_KEY_PASSWORD='fixture', NUVIO_RELEASE_KEY_ALIAS='fixture')
            command = [sys.executable, str(ROOT / 'scripts/prepare-signing.py')]
            result = subprocess.run(command, env=env, capture_output=True)
            self.assertEqual(result.returncode, 0)
            self.assertEqual(target.read_bytes(), b'fixture bytes')
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
            self.assertNotIn(b'fixture', result.stdout)
            result = subprocess.run(command, env=env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(target.read_bytes(), b'fixture bytes')

    def test_invalid_encoding_leaves_no_key(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / 'release.jks'
            env = dict(PATH=os.environ['PATH'], MOBILE_KEYSTORE_BASE64='invalid!', NUVIO_RELEASE_STORE_FILE=str(target), NUVIO_RELEASE_STORE_PASSWORD='fixture', NUVIO_RELEASE_KEY_PASSWORD='fixture', NUVIO_RELEASE_KEY_ALIAS='fixture')
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/prepare-signing.py')], env=env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(target.exists())

    def test_release_requires_service_registrations(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / 'local.properties'
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/configure-services.py'), str(target)], env={'PATH': os.environ['PATH'], 'REQUIRE_REGISTRATIONS': 'true'}, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(target.exists())
