#!/usr/bin/env bash
# Builds the signed release APK, writes its SHA-256 checksum and creates a local git tag.
# Usage: ./release.sh [--no-tag]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

die() { echo "error: $*" >&2; exit 1; }

TAG=true
case "${1:-}" in
  "") ;;
  --no-tag) TAG=false ;;
  *) die "usage: ./release.sh [--no-tag]" ;;
esac

# Reads "key=value" from a properties file; prints nothing if the file or key is missing.
prop() {
  [[ -f $1 ]] || return 0
  sed -n "s/^[[:space:]]*$2[[:space:]]*=[[:space:]]*//p" "$1" | head -n 1
}

# Environment variable first, then keystore.properties (same order as app/build.gradle.kts).
signing() {
  local value="${!2:-}"
  [[ -n $value ]] || value=$(prop keystore.properties "$1")
  printf '%s' "$value"
}

# --- Version (single source of truth: gradle.properties) ---
VERSION=$(prop gradle.properties hashira.versionName)
[[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] \
  || die "missing or invalid hashira.versionName in gradle.properties"

# --- Java ---
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$JAVA_BIN" >/dev/null || die "java not found: install a JDK 17 or newer"
JAVA_MAJOR=$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
(( ${JAVA_MAJOR:-0} >= 17 )) || die "JDK 17 or newer is required (found: ${JAVA_MAJOR:-unknown})"

# --- Android SDK ---
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n $SDK ]] || SDK=$(prop local.properties sdk.dir)
[[ -d $SDK ]] || die "Android SDK not found: set ANDROID_HOME (see README)"

# --- Signing configuration (secrets are never printed) ---
KEYSTORE=$(signing storeFile HASHIRA_KEYSTORE_FILE)
[[ -n $KEYSTORE ]] || die "no signing configuration: create keystore.properties (see keystore.properties.example)"
for pair in storePassword:HASHIRA_KEYSTORE_PASSWORD keyAlias:HASHIRA_KEY_ALIAS keyPassword:HASHIRA_KEY_PASSWORD; do
  [[ -n $(signing "${pair%%:*}" "${pair##*:}") ]] || die "missing signing value: ${pair%%:*}"
done
[[ $KEYSTORE == /* ]] || KEYSTORE="$PWD/$KEYSTORE"
[[ -f $KEYSTORE ]] || die "keystore not found: $KEYSTORE"

# --- Git (checked before building so a failed check leaves nothing behind) ---
if $TAG; then
  git rev-parse --git-dir >/dev/null 2>&1 || die "not a git repository (use --no-tag)"
  [[ -z $(git status --porcelain) ]] || die "uncommitted changes: commit or stash them (or use --no-tag)"
  git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && die "tag v$VERSION already exists"
fi

# --- Build ---
echo "Building hashira $VERSION ..."
./gradlew --console=plain clean assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
[[ -f $APK ]] || die "build finished but $APK was not produced"

# --- Verify the signature ---
APKSIGNER=$(find "$SDK/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -n 1 || true)
if [[ -n $APKSIGNER ]]; then
  CERTS=$("$APKSIGNER" verify --print-certs "$APK") || die "APK signature verification failed"
  grep 'SHA-256 digest' <<<"$CERTS" || true
else
  echo "warning: apksigner not found, signature not verified" >&2
fi

# --- Output ---
mkdir -p dist
cp "$APK" "dist/hashira-$VERSION.apk"
(cd dist && sha256sum "hashira-$VERSION.apk" > "hashira-$VERSION.apk.sha256")

if $TAG; then
  git tag -a "v$VERSION" -m "hashira $VERSION"
  echo "Created local tag v$VERSION (push it with: git push origin v$VERSION)"
fi

echo "Done:"
echo "  dist/hashira-$VERSION.apk"
echo "  dist/hashira-$VERSION.apk.sha256"
