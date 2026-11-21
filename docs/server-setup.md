# Mac mini に Orca Bot Remote サーバーを設置する

Mac mini と Android を同じ tailnet に接続します。Mac mini では Orca にログインして起動し、同じ macOS ユーザーのターミナルから作業します。スクリプトは `tailscale ip -4` と `~/.nvm/versions/node/v24.19.0/bin/node` を使います。

## 設置と更新

リポジトリのルートで実行します。

```sh
server/launchd/install.sh --dry-run
server/launchd/install.sh
```

`--dry-run` は生成した plist を標準出力に表示します。トークンの発行や LaunchAgent の登録は通常実行で行います。初回の通常実行でランダムなトークンを `~/.config/orca-bot-remote/token` に権限 `600` で保存します。インストーラーは保存場所を表示します。Android に設定する際は、そのファイルを信頼できる方法で開き、値を Android のトークン欄へ入力します。

インストーラーは `server/` を `~/Library/Application Support/orca-bot-remote/server/` にコピーします。この配置なら LaunchAgent が macOS の TCC で保護された Desktop にアクセスする必要がありません。plist は `~/Library/LaunchAgents/dev.okumura.orca-bot-remote.plist` に置きます。更新時は通常のインストールコマンドを再実行します。既存のジョブを停止し、コピーを更新してから再登録します。

インストール時に Orca から「専門Botの状況まとめ（毎日12:00）」を名前で検索し、見つかった ID を plist に書きます。自動化を後から作成・変更した場合は再実行してください。

## 動作確認

```sh
launchctl print "gui/$(id -u)/dev.okumura.orca-bot-remote"
host=$(tailscale ip -4)
printf 'Authorization: Bearer %s\n' "$(cat ~/.config/orca-bot-remote/token)" |
  curl --noproxy '*' --fail-with-body --silent --show-error --header @- "http://$host:8787/api/bots"
```

Bot 一覧を受け取れたら、Android から同じ IP または MagicDNS 名とポート `8787`、同じトークンで接続します。API のパスと応答は [API v1](api.md) を参照してください。

標準出力は `~/Library/Logs/orca-bot-remote/access.log` に保存され、1リクエストにつきパス、HTTP ステータス、所要時間が1行で出ます。起動エラーは同じディレクトリの `error.log` に出ます。

## 停止とアンインストール

一時停止する場合はジョブを登録解除します。再開は plist を再登録します。

```sh
launchctl bootout "gui/$(id -u)/dev.okumura.orca-bot-remote"
launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/dev.okumura.orca-bot-remote.plist"
```

アンインストールする場合は `server/launchd/uninstall.sh` を実行します。設置済みのサーバーと plist を削除します。トークンとログは再設置や調査に使えるよう残します。

## 環境変数

| 変数 | 設定 |
| --- | --- |
| `HOST` | 必須。Tailscale IPv4 `100.64.0.0/10` または IPv6 `fd7a:115c:a1e0::/48` の IP アドレス。インストーラーは Mac mini の Tailscale IPv4 を設定します |
| `PORT` | 既定 `8787` |
| `TOKEN_FILE` | 既定 `~/.config/orca-bot-remote/token`。権限 `600` の通常ファイル |
| `ORCA_BIN` | 既定 `/Applications/Orca.app/Contents/Resources/bin/orca`。絶対パス |
| `SUMMARY_AUTOMATION_ID` | まとめの自動化 ID。空の場合は `{"summary":null}` |

サーバーは指定した Tailscale IP にだけバインドします。接続先は tailnet 内のサーバーを指定してください。
