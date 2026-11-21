#!/bin/sh
set -eu

label=dev.okumura.orca-bot-remote
domain="gui/$(id -u)"

if /bin/launchctl print "$domain/$label" >/dev/null 2>&1; then
    /bin/launchctl bootout "$domain/$label"
fi
rm -f "$HOME/Library/LaunchAgents/$label.plist"
rm -rf "$HOME/Library/Application Support/orca-bot-remote/server"
