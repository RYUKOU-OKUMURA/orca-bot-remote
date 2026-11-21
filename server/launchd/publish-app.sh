#!/bin/sh
set -eu
umask 077

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$ANDROID_HOME/platform-tools:$PATH"
app_dir="${APP_DIR:-$HOME/Library/Application Support/orca-bot-remote/app}"
release_dir=android/app/build/outputs/apk/release

(cd android && ./gradlew assembleRelease)
apk_name=$(node --input-type=module -e '
import { readFileSync } from "node:fs";
const { elements } = JSON.parse(readFileSync(process.argv[1], "utf8"));
if (elements.length !== 1 || !["app-release.apk", "app-release-unsigned.apk"].includes(elements[0].outputFile)) {
  throw new Error("リリース APK を特定できません");
}
console.log(elements[0].outputFile);
' "$release_dir/output-metadata.json")
apk="$release_dir/$apk_name"
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs "$apk"

mkdir -p -m 700 "$app_dir"
chmod 700 "$app_dir"
apk_tmp=
json_tmp=
trap 'if [ -n "$apk_tmp" ]; then rm -f "$apk_tmp"; fi
      if [ -n "$json_tmp" ]; then rm -f "$json_tmp"; fi' EXIT
trap 'exit 1' HUP INT TERM
apk_tmp=$(mktemp "$app_dir/.apk.XXXXXX")
json_tmp=$(mktemp "$app_dir/.release.XXXXXX")

cp "$apk" "$apk_tmp"
node --input-type=module -e '
import { readFileSync } from "node:fs";
const { elements: [{ versionCode, versionName }] } = JSON.parse(readFileSync(process.argv[1], "utf8"));
if (!Number.isSafeInteger(versionCode) || versionCode < 1 ||
    typeof versionName !== "string" || versionName.length < 1 || versionName.length > 64 ||
    /[^ -~]/.test(versionName)) throw new Error("版番号が不正です");
console.log(JSON.stringify({ versionCode, versionName }));
' "$release_dir/output-metadata.json" > "$json_tmp"
chmod 600 "$apk_tmp" "$json_tmp"
sha256=$(/usr/bin/shasum -a 256 < "$apk_tmp")
release=$(cat "$json_tmp")

node --input-type=module -e '
import { renameSync } from "node:fs";
import { join } from "node:path";
const [apk, metadata, dir] = process.argv.slice(1);
renameSync(apk, join(dir, "orca-bot-remote.apk"));
renameSync(metadata, join(dir, "release.json"));
' "$apk_tmp" "$json_tmp" "$app_dir"
printf '%s\nSHA-256 %s\n' "$release" "${sha256%% *}"
