#!/usr/bin/env bash
# One-time provisioning: local Android SDK under tools/android-sdk + signing keystore.
# Idempotent — safe to re-run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="$ROOT/tools/android-sdk"
CACHE="/tmp/opencode/dl"

echo "==> Android SDK → $SDK"
mkdir -p "$SDK/platforms" "$SDK/build-tools"

# ---------------------------------------------------------------- cmdline-tools
if [ ! -d "$SDK/cmdline-tools/latest/bin" ]; then
  ZIP=""
  for c in "$CACHE/commandlinetools-linux-12700392_latest.zip" "/tmp/opencode/dl/cmdline-tools.zip"; do
    [ -f "$c" ] && ZIP="$c" && break
  done
  if [ -z "$ZIP" ]; then
    ZIP="$CACHE/commandlinetools.zip"
    mkdir -p "$CACHE"
    curl -fL --retry 3 -o "$ZIP" "https://dl.google.com/android/repository/commandlinetools-linux-12700392_latest.zip"
  fi
  STAGE="$(mktemp -d)"
  unzip -q -o "$ZIP" -d "$STAGE"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$STAGE/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$STAGE"
  echo "  + cmdline-tools"
fi

# ---------------------------------------------------------- platforms;android-35
if [ ! -f "$SDK/platforms/android-35/android.jar" ]; then
  ZIP=""
  [ -f "$CACHE/platform35.zip" ] && ZIP="$CACHE/platform35.zip"
  if [ -z "$ZIP" ]; then
    mkdir -p "$CACHE"
    ZIP="$CACHE/platform-35.zip"
    curl -fL --retry 3 -o "$ZIP" "https://dl.google.com/android/repository/platform-35_r02.zip" \
      || curl -fL --retry 3 -o "$ZIP" "https://dl.google.com/android/repository/platform-35_r01.zip"
  fi
  STAGE="$(mktemp -d)"
  unzip -q -o "$ZIP" -d "$STAGE"
  rm -rf "$SDK/platforms/android-35"
  mv "$STAGE/android-35" "$SDK/platforms/android-35"
  rm -rf "$STAGE"
  echo "  + platforms/android-35"
fi

# ----------------------------------------------------------- build-tools;34.0.0
if [ ! -x "$SDK/build-tools/34.0.0/aapt2" ]; then
  ZIP=""
  [ -f "$CACHE/build-tools-34.zip" ] && ZIP="$CACHE/build-tools-34.zip"
  if [ -z "$ZIP" ]; then
    mkdir -p "$CACHE"
    ZIP="$CACHE/build-tools-34.zip"
    curl -fL --retry 3 -o "$ZIP" "https://dl.google.com/android/repository/build-tools_r34-linux.zip"
  fi
  STAGE="$(mktemp -d)"
  unzip -q -o "$ZIP" -d "$STAGE"
  rm -rf "$SDK/build-tools/34.0.0"
  mkdir -p "$SDK/build-tools/34.0.0"
  if [ -d "$STAGE/android-14" ]; then   # some zips nest under a version dir
    mv "$STAGE/android-14"/* "$SDK/build-tools/34.0.0/"
  else
    cp -a "$STAGE/." "$SDK/build-tools/34.0.0/"
  fi
  rm -rf "$STAGE"
  echo "  + build-tools/34.0.0"
fi

# ---------------------------------------------------------- platform-tools (adb)
if [ ! -x "$SDK/platform-tools/adb" ]; then
  mkdir -p "$CACHE"
  ZIP="$CACHE/platform-tools.zip"
  [ -f "$ZIP" ] || curl -fL --retry 3 -o "$ZIP" "https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
  rm -f "$SDK/platform-tools/adb" 2>/dev/null || true
  mkdir -p "$SDK/platform-tools"
  unzip -q -o "$ZIP" -d "$SDK/platform-tools"
  echo "  + platform-tools"
fi

# --------------------------------------------------------------------- licenses
mkdir -p "$SDK/licenses"
cat > "$SDK/licenses/android-sdk-license" <<'EOF'
89e2d532f21be55d8fc8fbd3ea398ad7
533cd16c72d9ad799a23197574dc257e
24333f8a63b6825ea9c5514f83c28295
d56f5187cf7cc2011a0d28e73cfd622a
84839b9796aa7b5434d05ad9337f07fd1ccd18f3
1358f2893cbb6406f08d1f6130600f1f
EOF
cat > "$SDK/licenses/android-sdk-preview-license" <<'EOF'
84839b9796aa7b5434d05ad9337f07fd1ccd18f3
EOF

# -------------------------------------------------------------- local.properties
printf 'sdk.dir=%s\n' "$SDK" > "$ROOT/local.properties"
echo "  + local.properties"

# -------------------------------------------------------------------- signing
mkdir -p "$ROOT/config"
if [ ! -f "$ROOT/config/motionwake.keystore" ]; then
  echo "==> Generating release keystore"
  keytool -genkeypair -v \
    -keystore "$ROOT/config/motionwake.keystore" \
    -alias motionwake \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass motionwake -keypass motionwake \
    -dname "CN=MotionWake, OU=egraf, O=egraf, L=Local, ST=Local, C=US"
fi
cat > "$ROOT/config/signing.properties" <<'EOF'
storeFile=config/motionwake.keystore
storePassword=motionwake
keyAlias=motionwake
keyPassword=motionwake
EOF
echo "  + config/signing.properties"

echo "==> Setup complete."
