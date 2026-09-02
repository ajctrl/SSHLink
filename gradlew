#!/usr/bin/env sh
set -eu

GRADLE_VERSION=9.5.0
GRADLE_SHA256=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746
BASE_DIR=${GRADLE_USER_HOME:-"$HOME/.gradle"}/sshlink-bootstrap
ZIP="$BASE_DIR/gradle-$GRADLE_VERSION-bin.zip"
DIST="$BASE_DIR/gradle-$GRADLE_VERSION"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE_DIR"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl --fail --location --retry 3 --output "$ZIP" "$URL"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP" "$URL"
    else
      echo "curl or wget is required to bootstrap Gradle" >&2
      exit 1
    fi
  fi
  ACTUAL=$(sha256sum "$ZIP" | awk '{print $1}')
  if [ "$ACTUAL" != "$GRADLE_SHA256" ]; then
    echo "Gradle distribution checksum mismatch" >&2
    rm -f "$ZIP"
    exit 1
  fi
  rm -rf "$DIST"
  unzip -q -o "$ZIP" -d "$BASE_DIR"
fi

exec "$DIST/bin/gradle" "$@"
