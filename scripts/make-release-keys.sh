#!/usr/bin/env bash
# Generates a brand-new Android release keystore + the CI secrets for the "prod" GitHub environment.
# Everything is fresh and random: no reuse of the debug keystore, other projects' keys or personal identity.
# Output goes to keys/ (gitignored). Re-running does NOT overwrite an existing keystore (delete keys/ first).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEYS="$ROOT/keys"
mkdir -p "$KEYS"
chmod 700 "$KEYS"

# keytool: JAVA_HOME, PATH, or Android Studio's bundled JDK on macOS
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then KEYTOOL="$JAVA_HOME/bin/keytool"
elif command -v keytool >/dev/null 2>&1; then KEYTOOL="$(command -v keytool)"
elif [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" ]; then KEYTOOL="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
else echo "keytool not found: install a JDK or set JAVA_HOME" >&2; exit 1; fi

rand() { local s; s="$(openssl rand -base64 192 | LC_ALL=C tr -dc 'A-Za-z0-9')"; printf '%s' "${s:0:$1}"; }

KS="$KEYS/dg-tempo-release.keystore"
ALIAS="dgtempo-release"
if [ -f "$KS" ]; then
  echo "keystore already exists: $KS (delete the keys/ folder to start over)" >&2
  exit 1
fi

KS_PASS="$(rand 32)"
KEY_PASS="$KS_PASS"      # PKCS12 keystores use ONE password for store and key (keytool ignores a different -keypass)
PUBLISH_SECRET="$(rand 48)"

# Generic subject on purpose: nothing that identifies a person or another project.
"$KEYTOOL" -genkeypair -v \
  -keystore "$KS" -storetype PKCS12 -storepass "$KS_PASS" \
  -alias "$ALIAS" -keypass "$KEY_PASS" \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=DG Tempo Release, OU=Android, O=DG Tempo, C=US" >/dev/null 2>&1

FINGERPRINT="$("$KEYTOOL" -list -v -keystore "$KS" -storepass "$KS_PASS" -alias "$ALIAS" 2>/dev/null | grep 'SHA256:' | head -1 | sed 's/^[[:space:]]*//')"
KS_B64="$(base64 < "$KS" | tr -d '\n')"

OUT="$KEYS/github-secrets.txt"
umask 077
cat > "$OUT" <<TXT
# DG Tempo release secrets — generated $(date -u +%Y-%m-%dT%H:%M:%SZ)
# Paste each value into GitHub → repo Settings → Environments → prod → Environment secrets.
# APK_PUBLISH_SECRET must ALSO be added to the sneakers-dance Vercel project env (same value).
# Keep this folder private. Losing the keystore means every phone must uninstall/reinstall.
#
# keystore file : $KS
# alias         : $ALIAS
# certificate   : $FINGERPRINT

APK_PUBLISH_SECRET=$PUBLISH_SECRET
ANDROID_KEYSTORE_PASSWORD=$KS_PASS
ANDROID_KEY_ALIAS=$ALIAS
ANDROID_KEY_PASSWORD=$KEY_PASS
ANDROID_KEYSTORE_B64=$KS_B64
TXT

# one file per secret: select-all + copy, nothing else to trim
for NAME in APK_PUBLISH_SECRET ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD ANDROID_KEYSTORE_B64; do
  VAL="$(grep "^$NAME=" "$OUT" | cut -d= -f2-)"
  printf '%s' "$VAL" > "$KEYS/$NAME.txt"
done

echo "keystore : $KS"
echo "secrets  : $OUT"
echo "cert     : $FINGERPRINT"
