"""Retain a private, UNPUBLISHED draft when Actions artifact quota is full."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from zipfile import ZipFile, ZIP_DEFLATED

repo = os.environ['GITHUB_REPOSITORY']
sha = os.environ['GITHUB_SHA']
assert re.fullmatch(r'[0-9a-f]{40}', sha)
debug = os.environ.get('CANDIDATE_VARIANT') == 'debug'
test_number = re.search(r'^experimentalTestNumber=(\d+)$', Path('gradle.properties').read_text(), re.M)[1]
tag = f'private-test-{test_number}' if debug else 'private-candidate-1.0.3'


def api(path, payload=None, method=None):
    args = ['gh', 'api', path]
    if method:
        args += ['--method', method]
    if payload is not None:
        args += ['--input', '-']
    call = subprocess.run(args, input=json.dumps(payload) if payload is not None else None,
                          capture_output=True, text=True)
    if call.returncode:
        raise RuntimeError('Candidate storage API failed: ' + path)
    return json.loads(call.stdout) if call.stdout.strip() else None


def has_tag():
    return subprocess.run(['gh', 'api', f'repos/{repo}/git/ref/tags/{tag}'],
                          capture_output=True).returncode == 0


assets = Path('candidate-assets')
assets.mkdir(exist_ok=True)
paths = []
for source, destination in [
    (Path('app/build/outputs/apk/full/debug/app-full-universal-debug.apk') if debug else Path('app/build/outputs/apk/full/release/app-full-universal-release.apk'),
     f'NuvioTV-Test-{test_number}-{sha}.apk' if debug else f'NuvioTV-1.0.3-{sha}.apk'),
    (Path('release-candidate-verification.json'), f'verification-{sha}.json'),
]:
    if source.is_file():
        target = assets / destination
        shutil.copyfile(source, target)
        paths.append(target)
reports = list(Path('app/build/test-results/testFullDebugUnitTest').glob('TEST-*.xml'))
mapping = Path('app/build/outputs/mapping/fullRelease/mapping.txt')
if reports or mapping.exists():
    target = assets / f'diagnostics-{sha}.zip'
    with ZipFile(target, 'w', ZIP_DEFLATED) as archive:
        for report in reports:
            archive.write(report, 'tests/' + report.name)
        if mapping.exists():
            archive.write(mapping, 'mapping.txt')
    paths.append(target)
if not paths:
    print('No candidate files available; nothing stored.')
    raise SystemExit(0)
assert not has_tag(), 'Refusing to modify an existing candidate tag'
existing = None
for page in range(1, 100):
    releases = api(f'repos/{repo}/releases?per_page=100&page={page}')
    existing = next((r for r in releases if r['tag_name'] == tag), None)
    if existing or len(releases) < 100:
        break
if existing:
    assert existing['draft'] and existing['published_at'] is None, 'Refusing to modify a published release'
payload = {'tag_name': tag, 'target_commitish': sha, 'draft': True, 'prerelease': True,
           'name': f'Test {test_number} private candidate (UNPUBLISHED)' if debug else '1.0.3 private test candidate (UNPUBLISHED)',
           'body': f'Private build storage only. Do not publish before owner testing/approval.\n\nSource: {sha}\nWorkflow: {os.environ.get("GITHUB_RUN_ID")}\nBuild status before storage: {os.environ.get("CANDIDATE_BUILD_STATUS", "unknown")}\nAPK must pass the exact-source verification receipt before installation.'}
path = f'repos/{repo}/releases' + (f'/{existing["id"]}' if existing else '')
draft = api(path, payload, 'PATCH' if existing else 'POST')
assert draft['draft'] and draft['published_at'] is None
upload = subprocess.run(['gh', 'release', 'upload', tag, '--repo', repo, '--clobber', *map(str, paths)],
                        capture_output=True, text=True)
assert upload.returncode == 0, 'Private candidate asset upload failed'
check = api(f'repos/{repo}/releases/{draft["id"]}')
assert check['draft'] and check['published_at'] is None, 'Candidate must stay unpublished'
assert not has_tag(), 'Draft unexpectedly created a tag'
print('Stored unpublished private candidate:', check['html_url'])
