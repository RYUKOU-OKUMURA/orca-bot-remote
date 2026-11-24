#!/bin/sh
set -eu
umask 077

if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != '--dry-run' ]; }; then
    printf '使い方  %s [--dry-run]\n' "$0" >&2
    exit 2
fi

server_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
label=dev.okumura.orca-bot-remote
app_dir="$HOME/Library/Application Support/orca-bot-remote"
install_dir="$app_dir/server"
log_dir="$HOME/Library/Logs/orca-bot-remote"
agent_dir="$HOME/Library/LaunchAgents"
token_file="$HOME/.config/orca-bot-remote/token"
find_node() {
    [ -f "$1" ] && [ -x "$1" ] || return 1
    node_dir=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd) || return 1
    candidate="$node_dir/$(basename -- "$1")"
    major=$("$candidate" -p 'process.versions.node.split(".")[0]' 2>/dev/null) || return 1
    [ "$major" -ge 24 ] 2>/dev/null || return 1
    printf '%s\n' "$candidate"
}

if [ "${NODE_BIN+x}" = x ]; then
    if ! node_bin=$(find_node "$NODE_BIN"); then
        printf 'NODE_BIN には実行可能な Node.js 24 以上のパスを指定してください。\n' >&2
        exit 1
    fi
else
    node_bin=
    nvm_root="${NVM_DIR:-$HOME/.nvm}/versions/node"
    # Sort version names only, so spaces in the installation path are preserved.
    versions=$(for dir in "$nvm_root"/v[0-9]*; do
        if [ -x "$dir/bin/node" ]; then basename -- "$dir"; fi
    done | sort -Vr)
    for version in $versions; do
        if node_bin=$(find_node "$nvm_root/$version/bin/node"); then break; fi
    done
    if [ -z "$node_bin" ] && ! node_bin=$(find_node "$(command -v node || true)"); then
        printf 'Node.js 24 以上が必要です。NODE_BIN に実行ファイルのパスを指定してください。\n' >&2
        exit 1
    fi
fi
orca_bin=/Applications/Orca.app/Contents/Resources/bin/orca
if [ "${1:-}" = '--dry-run' ]; then
    orca_bin=${ORCA_BIN:-$orca_bin}
fi
domain="gui/$(id -u)"

host=$(tailscale ip -4)
(
    cd "$server_dir/src"
    "$node_bin" --input-type=module -e '
      import { isTailscaleAddress } from "./config.ts";
      if (!process.argv[1].includes(".") || !isTailscaleAddress(process.argv[1])) {
        throw new Error("tailscale ip -4 から Tailscale の IPv4 アドレスを取得できません。");
      }
    ' -- "$host"
)

summary_id=${SUMMARY_AUTOMATION_ID:-}
if [ -z "$summary_id" ]; then
    automations=$(env -i HOME="$HOME" PATH=/usr/bin:/bin "$orca_bin" automations list --json)
    summary_id=$(printf '%s' "$automations" | "$node_bin" --input-type=module -e '
      let input = "";
      for await (const chunk of process.stdin.setEncoding("utf8")) input += chunk;
      const reply = JSON.parse(input);
      if (reply.ok !== true) throw new Error("Orca の自動化一覧を取得できません。");
      const { automations } = reply.result;
      const match = automations.find(item => item.name === "秘書まとめ（朝8:00・昼12:00・夕18:00＋放置リマインド）")
        ?? automations.find(item => item.name === "専門Botの状況まとめ（毎日12:00）")
        ?? automations.find(item => item.enabled !== false && typeof item.name === "string" && item.name.includes("まとめ"));
      process.stdout.write(match?.id ?? "");
    ')
fi
if [ -z "$summary_id" ]; then
    printf '警告: まとめ用の自動化が見つかりません。まとめは未設定になります。\n' >&2
fi

plist=$(mktemp "${TMPDIR:-/tmp}/orca-bot-remote.XXXXXXXX")
token_tmp=
stage=
agent_plist="$agent_dir/$label.plist"
rollback=false
restart_old=false
# bootout returns before the job exits; bootstrap fails with 5 until it is gone.
# launchd sends SIGKILL after ExitTimeOut (default 20s), so 30s is enough.
wait_until_unloaded() {
    tries=0
    while /bin/launchctl print "$domain/$label" >/dev/null 2>&1; do
        tries=$((tries + 1))
        if [ "$tries" -gt 60 ]; then
            printf '旧ジョブが約30秒待っても停止しません  %s\n' "$domain/$label" >&2
            return 1
        fi
        sleep 0.5
    done
}
restore_previous() {
    if [ ! -d "$stage/new" ]; then
        mv "$install_dir" "$stage/new" || return 1
    fi
    if [ -e "$stage/previous" ] || [ -L "$stage/previous" ]; then
        mv "$stage/previous" "$install_dir" || return 1
    fi
    if [ -f "$stage/previous.plist" ]; then
        cp -p "$stage/previous.plist" "$agent_plist" || return 1
        if "$restart_old"; then
            wait_until_unloaded || return 1
            /bin/launchctl bootstrap "$domain" "$agent_plist" || return 1
        fi
    else
        rm -f "$agent_plist" || return 1
    fi
}
cleanup() {
    rm -f "$plist"
    if [ -n "$token_tmp" ]; then rm -f "$token_tmp"; fi
    if "$rollback" && ! restore_previous; then
        printf '旧版の復元が完了しませんでした。退避先を確認してください  %s\n' "$stage" >&2
        exit 1
    fi
    if [ -n "$stage" ]; then rm -rf "$stage"; fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
cp "$server_dir/launchd/$label.plist" "$plist"
/usr/bin/plutil -replace ProgramArguments -array "$plist"
/usr/bin/plutil -insert ProgramArguments -string "$node_bin" -append "$plist"
/usr/bin/plutil -insert ProgramArguments -string "$install_dir/src/main.ts" -append "$plist"
/usr/bin/plutil -replace EnvironmentVariables.HOST -string "$host" "$plist"
/usr/bin/plutil -replace EnvironmentVariables.SUMMARY_AUTOMATION_ID -string "$summary_id" "$plist"
/usr/bin/plutil -replace StandardOutPath -string "$log_dir/access.log" "$plist"
/usr/bin/plutil -replace StandardErrorPath -string "$log_dir/error.log" "$plist"

if [ "${1:-}" = '--dry-run' ]; then
    cat "$plist"
    exit 0
fi

mkdir -p "$agent_dir" "$log_dir" "$app_dir" "$(dirname -- "$token_file")"
if [ ! -e "$token_file" ]; then
    token_tmp=$(mktemp "$token_file.XXXXXXXX")
    /usr/bin/openssl rand -hex 32 > "$token_tmp"
    chmod 600 "$token_tmp"
    mv -n "$token_tmp" "$token_file"
fi
(
    cd "$server_dir/src"
    HOST="$host" TOKEN_FILE="$token_file" "$node_bin" --input-type=module -e '
      import { readConfig } from "./config.ts";
      readConfig();
    '
)

stage=$(mktemp -d "$app_dir/.server.XXXXXXXX")
mkdir "$stage/new"
cp -R "$server_dir/package.json" "$server_dir/package-lock.json" "$server_dir/src" "$server_dir/vendor" "$stage/new/"
node_dir=$(dirname -- "$node_bin")
PATH="$node_dir:$PATH" npm ci --prefix "$stage/new" --omit=dev --ignore-scripts --no-audit --no-fund

if [ -f "$agent_plist" ]; then cp -p "$agent_plist" "$stage/previous.plist"; fi
if /bin/launchctl print "$domain/$label" >/dev/null 2>&1; then
    /bin/launchctl bootout "$domain/$label"
    restart_old=true
    rollback=true
    wait_until_unloaded
fi
rollback=true
if [ -e "$install_dir" ] || [ -L "$install_dir" ]; then mv "$install_dir" "$stage/previous"; fi
mv "$stage/new" "$install_dir"
cp "$plist" "$agent_plist"
/bin/launchctl bootstrap "$domain" "$agent_plist"
rollback=false
printf 'トークンファイル %s\n' "$token_file"
