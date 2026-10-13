#!/usr/bin/env bash
# Build debug + release APKs (runs setup.sh first if needed).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ ! -f local.properties ]; then
  ./scripts/setup.sh
fi
chmod +x gradlew 2>/dev/null || true

./gradlew :app:assembleDebug :app:assembleRelease

echo
echo "APKs:"
ls -la app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release.apk 2>/dev/null || true
echo
echo "Deploy with: ./scripts/deploy.sh [adb-host:port]"
