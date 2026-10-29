#!/usr/bin/env python3
"""Apply the independent TV app identity to a checked-out NuvioTV 1.0.0 tree."""
from pathlib import Path
import argparse

p = argparse.ArgumentParser()
p.add_argument("source", type=Path)
args = p.parse_args()
gradle = args.source / "app/build.gradle.kts"
content = gradle.read_text(encoding="utf-8")
replacements = {
    'applicationId = "com.nuvio.tv"': 'applicationId = "com.ali92barca.nuviotv.cinemana"',
    'versionName = "1.0.0"': 'versionName = "1.0.0-cinemana.1"',
    'buildConfigField("String", "GITHUB_OWNER", "\\"NuvioMedia\\"")': 'buildConfigField("String", "GITHUB_OWNER", "\\"Ali92Barca\\"")',
    'buildConfigField("String", "GITHUB_REPO", "\\"NuvioTV\\"")': 'buildConfigField("String", "GITHUB_REPO", "\\"NuvioTV-Cinemana\\"")',
}
for old, new in replacements.items():
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one occurrence of {old!r}, found {count}")
    content = content.replace(old, new, 1)
gradle.write_text(content, encoding="utf-8")
print("Applied independent application ID, version and update repository.")
