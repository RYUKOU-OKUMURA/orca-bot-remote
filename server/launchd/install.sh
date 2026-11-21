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
node_bin="$HOME/.nvm/versions/node/v24.19.0/bin/node"
orca_bin=/Applications/Orca.app/Contents/Resources/bin/orca
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

automations=$(env -i HOME="$HOME" PATH=/usr/bin:/bin "$orca_bin" automations list --json)
summary_id=$(printf '%s' "$automations" | "$node_bin" --input-type=module -e '
  let input = "";
  for await (const chunk of process.stdin.setEncoding("utf8")) input += chunk;
  const reply = JSON.parse(input);
  if (reply.ok !== true) throw new Error("Orca の自動化一覧を取得できません。");
  const match = reply.result.automations.find(item => item.name === "専門Botの状況まとめ（毎日12:00）");
  process.stdout.write(match?.id ?? "");
')

plist=$(mktemp "${TMPDIR:-/tmp}/orca-bot-remote.XXXXXXXX")
token_tmp=
cleanup() {
    rm -f "$plist"
    if [ -n "$token_tmp" ]; then rm -f "$token_tmp"; fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
cp "$server_dir/launchd/$label.plist" "$plist"
/usr/bin/plutil -replace ProgramArguments.0 -string "$node_bin" "$plist"
/usr/bin/plutil -replace ProgramArguments.1 -string "$install_dir/src/main.ts" "$plist"
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

if /bin/launchctl print "$domain/$label" >/dev/null 2>&1; then
    /bin/launchctl bootout "$domain/$label"
fi
/usr/bin/ditto "$server_dir" "$install_dir"
cp "$plist" "$agent_dir/$label.plist"
/bin/launchctl bootstrap "$domain" "$agent_dir/$label.plist"
printf 'トークンファイル %s\n' "$token_file"
