# NuvioTV-Cinemana

Independent Android TV integration over official NuvioMedia/NuvioTV 1.0.0,
pinned to `9f17e8bf4abc799dc8c832d2894a8b3b166e4353`.

The maintained overlay retains the upstream Android TV UI, focus navigation and player.
Cinemana is handled natively inside AddonApi; no external Node process is needed.

## Build

`bash scripts/prepare-tv.sh` then
`python3 scripts/apply-tv-overlay.py .work/NuvioTV` and
`python3 scripts/check-tv-config.py .work/NuvioTV`.

The build workflow runs provider contract tests and Android unit tests before assembling
FullDebug APKs. Release never falls back to a debug or upstream signing key.
See [validation](docs/TV_VALIDATION.md) for tested status and remaining work.
