"""Verify the private Release candidate; never print registration credentials."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from apk_storage_schema import defined_fields

apk = Path(sys.argv[1])
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
badging = subprocess.run([str(tools / 'aapt'), 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True).stdout
assert "name='com.ali92barca.nuviotv'" in badging, 'Wrong package'
assert "versionName='1.0.4'" in badging and "versionCode='8'" in badging, 'Wrong version'
assert 'application-debuggable' not in badging, 'Release must not be debuggable'
configs = [p for p in Path('app/build/generated/source/buildConfig').rglob('BuildConfig.java') if 'release' in str(p).lower()]
assert len(configs) == 1, 'Expected one release service configuration'
config = configs[0].read_text()
assert re.search(r'IS_DEBUG_BUILD\s*=\s*false', config), 'Wrong build type'
required = ['SUPABASE_URL', 'SUPABASE_ANON_KEY', 'TRAKT_CLIENT_ID', 'SIMKL_CLIENT_ID', 'TMDB_API_KEY']
for name in required:
    value = re.search(r'\b' + name + r'\s*=\s*"([^"]*)"', config)
    assert value and value[1].strip(), 'Missing registered service: ' + name

schemas = {
    'com.nuvio.tv.data.repository.SharedPlaybackIdentity$ExternalEpisode': ['scope', 'native', 'nativeVideo', 'canonical', 'nativeSeason', 'nativeEpisode', 'canonicalSeason', 'canonicalEpisode'],
    'com.nuvio.tv.data.repository.SharedPlaybackIdentity$Stored': ['native', 'canonical', 'series', 'aliases', 'episodes', 'partial'],
    'com.nuvio.tv.data.repository.ExternalMovieIdentity': ['scope', 'native', 'tmdb', 'imdb'],
    'com.nuvio.tv.core.sync.PendingProgressUpsert': ['key', 'progress'],
    'com.nuvio.tv.core.sync.PendingWatchedUpsert': ['key', 'item'],
    'com.nuvio.tv.core.sync.ProgressDeleteVersion': ['key', 'revision'],
    'com.nuvio.tv.core.sync.WatchedDeleteVersion': ['key', 'revision'],
    'com.nuvio.tv.data.builtincache.BuiltinDetailsStore$EpisodeKey': ['id', 'season', 'episode'],
    'com.nuvio.android.cinemana.CinemanaTmdbMatcher$Resolution': ['match', 'confirmedMissing'],
    'com.nuvio.android.cinemana.CinemanaTmdbMatcher$Match': ['tmdbId'],
}
actual = defined_fields(apk, schemas)
for cls, fields in schemas.items():
    assert cls in actual, 'R8 storage class missing/renamed: ' + cls
    missing = set(fields) - actual[cls]
    assert not missing, 'R8 storage fields missing/renamed: ' + cls + ': ' + ', '.join(sorted(missing))

receipt = {'commit': os.environ.get('GITHUB_SHA'), 'package': 'com.ali92barca.nuviotv', 'versionName': '1.0.4', 'versionCode': 8, 'debuggable': False, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'registeredServices': required, 'stableStorageSchemas': list(schemas)}
Path('release-candidate-verification.json').write_text(json.dumps(receipt, indent=2))
print('Verified full registered Release 1.0.4, signing checked separately; all persisted JSON field names preserved by R8.')
