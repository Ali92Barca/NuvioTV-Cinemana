#!/usr/bin/env bash
set -euo pipefail
UPSTREAM_URL="https://github.com/NuvioMedia/NuvioTV.git"
UPSTREAM_REF="1.0.0"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${1:-$ROOT/.work/NuvioTV}"
mkdir -p "$(dirname "$TARGET")"
if [[ -e "$TARGET" ]]; then echo "Target already exists: $TARGET" >&2; exit 1; fi
git clone --depth 1 --branch "$UPSTREAM_REF" "$UPSTREAM_URL" "$TARGET"
echo "Prepared NuvioTV $UPSTREAM_REF at $TARGET"
test "$(git -C "$TARGET" rev-parse HEAD)" = "9f17e8bf4abc799dc8c832d2894a8b3b166e4353"

