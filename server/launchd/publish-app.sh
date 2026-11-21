#!/bin/sh
set -eu
umask 077

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$ANDROID_HOME/platform-tools:$PATH"
app_dir="${APP_DIR:-$HOME/Library/Application Support/orca-bot-remote/app}"
release_dir=android/app/build/outputs/apk/release

if [ "$(git rev-parse --is-shallow-repository)" != false ]; then
    printf '%s\n' 'アプリの公開には Git の完全な履歴が必要です。' >&2
    exit 1
fi

(cd android && ./gradlew assembleRelease)
artifact=$(node --input-type=module -e '
import { readFileSync } from "node:fs";
const { elements } = JSON.parse(readFileSync(process.argv[1], "utf8"));
if (elements.length !== 1 || !["app-release.apk", "app-release-unsigned.apk"].includes(elements[0].outputFile)) {
  throw new Error("リリース APK を特定できません");
}
const { outputFile, versionCode, versionName } = elements[0];
if (!Number.isSafeInteger(versionCode) || versionCode < 1 ||
    typeof versionName !== "string" || versionName.length < 1 || versionName.length > 64 ||
    /[^ -~]/.test(versionName)) throw new Error("版番号が不正です");
console.log(outputFile, JSON.stringify({ versionCode, versionName }));
' "$release_dir/output-metadata.json")
apk_name="${artifact%% *}"
release="${artifact#* }"

mkdir -p -m 700 "$app_dir"
chmod 700 "$app_dir"
apk_tmp=
json_tmp=
trap 'if [ -n "$apk_tmp" ]; then rm -f "$apk_tmp"; fi
      if [ -n "$json_tmp" ]; then rm -f "$json_tmp"; fi' EXIT
trap 'exit 1' HUP INT TERM
apk_tmp=$(mktemp "$app_dir/.apk.XXXXXX")
json_tmp=$(mktemp "$app_dir/.release.XXXXXX")

cp "$release_dir/$apk_name" "$apk_tmp"
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs "$apk_tmp"
printf '%s\n' "$release" > "$json_tmp"
chmod 600 "$apk_tmp" "$json_tmp"
sha256=$(/usr/bin/shasum -a 256 < "$apk_tmp")

node --input-type=module -e '
import { mkdirSync, readFileSync, renameSync, rmdirSync } from "node:fs";
import { join } from "node:path";
const [apk, metadata, dir, release] = process.argv.slice(1);
const releasePath = join(dir, "release.json");
const lock = join(dir, ".publish-lock");
mkdirSync(lock);
try {
  let current;
  try {
    current = readFileSync(releasePath, "utf8");
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  if (current !== undefined) {
    const { versionCode } = JSON.parse(current);
    if (!Number.isSafeInteger(versionCode) || versionCode < 1) throw new Error("公開中の版番号が不正です");
    if (JSON.parse(release).versionCode < versionCode) throw new Error("公開中の版番号を下回っています");
  }
  renameSync(apk, join(dir, "orca-bot-remote.apk"));
  renameSync(metadata, releasePath);
} finally {
  rmdirSync(lock);
}
' "$apk_tmp" "$json_tmp" "$app_dir" "$release"
printf '%s\nSHA-256 %s\n' "$release" "${sha256%% *}"
