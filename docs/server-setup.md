# Mac mini に Orca Bot Remote サーバーを設置する

Mac mini と Android を同じ tailnet に接続します。Mac mini では Orca にログインして起動し、同じ macOS ユーザーのターミナルから作業します。インストーラーは `tailscale ip -4` で待受アドレスを取得します。`NODE_BIN` を指定する場合は、実行可能な Node.js 24 以上のパスが必要です。指定が無効な場合はエラーで終了します。未指定の場合は nvm の新しいバージョンから順に探し、続いて PATH 上の `node` を確認します。通常の設置には npm も必要です。

## 設置と更新

リポジトリのルートで実行します。

```sh
server/launchd/install.sh --dry-run
server/launchd/install.sh
```

`--dry-run` は生成した plist を標準出力に表示します。`ProgramArguments` は Node と `main.ts` の絶対パスの2要素です。トークンの発行や LaunchAgent の登録は通常実行で行います。初回の通常実行でランダムなトークンを `~/.config/orca-bot-remote/token` に権限 `600` で保存します。インストーラーは保存場所を表示します。Android に設定する際は、そのファイルを信頼できる方法で開き、値を Android のトークン欄へ入力します。

インストーラーは一時ディレクトリに実行用のファイルと本番依存を揃え、`~/Library/Application Support/orca-bot-remote/server/` を置き換えます。この配置なら LaunchAgent が macOS の TCC で保護された Desktop にアクセスする必要がありません。plist は `~/Library/LaunchAgents/dev.okumura.orca-bot-remote.plist` に置きます。更新時は通常のインストールコマンドを再実行します。配置の準備が済んだら既存のジョブを停止し、旧版を一時退避して更新します。新しいジョブの登録に成功すると退避先を削除します。配置や登録に失敗した場合は旧版を戻し、更新前に稼働していたジョブを再登録します。

インストール時に環境変数 `SUMMARY_AUTOMATION_ID` が非空なら、その ID を plist に書きます。未指定または空の場合は、Orca の自動化一覧から「秘書まとめ（朝8:00・昼12:00・夕18:00＋放置リマインド）」、旧名「専門Botの状況まとめ（毎日12:00）」の順で完全一致を探します。両方とも見つからなければ、名前に「まとめ」を含み、無効化されていない最初の自動化を選びます。候補が無い場合は警告を表示し、まとめを未設定にして設置を続けます。設定を反映するにはインストーラーを再実行してください。

## 本人として書き込む端末をペアリングする

Orca を起動し、Settings → Mobile の端末一覧を控えます。設置と同じ macOS ユーザーのターミナルから、リポジトリのルートで次を実行します。

```sh
npm --prefix server ci
node server/src/pair.ts
```

スクリプトはモバイル招待を端末トークンに交換し、`~/.config/orca-bot-remote/orca-device.json` に権限 `600` で保存します。既存のファイルがあれば発行をスキップします。招待と資格情報は表示されません。端末名には Orca が付ける `Mobile <日付>` が使われます。配布版の `orca pair --mobile` には名前を指定するオプションがありません。

Mac mini では `127.0.0.1:6768` への TCP 接続とペアリング後の E2EE 認証を確認したため、Orca への接続に loopback を使います。スクリプトは loopback へ届かない場合に Tailscale IPv4 の到達性を確認します。Orca のネットワークペアリングは初回に Orca 自身の待ち受けを広げることがあります。検証した Mac mini では、ペアリング前から Orca が全 IPv4 インターフェースの TCP 6768 で待ち受けていました。Android が接続する中継サーバーは Tailscale IP にバインドします。

登録後に Settings → Mobile を開き、登録前の端末一覧との差分でサーバー用の端末を特定して控えます。取り消すときは、この端末を一覧から削除します。資格情報ファイルが無い、または失効した場合は書き込みが `503 write_unavailable` になり、読み取りは利用できます。

再ペアリングするときは端末一覧を控えてから、`node server/src/pair.ts --force` を実行します。新しいトークンで認証が成功すると、権限 `600` の資格情報ファイルが原子的に切り替わります。書き込みを確認したら、Settings → Mobile から旧端末を取り消します。交換や検証に失敗した場合、既存ファイルは維持されます。新しい端末だけが登録された場合は、差分で特定して取り消してください。

中断後に `~/.config/orca-bot-remote/.orca-device-pairing` が残った場合は、別のペアリングが実行中でないことを確認します。端末一覧の差分と現在の資格情報による書き込みを確認し、不要と特定できた登録だけを取り消します。登録状況が不明な場合は再登録を保留してください。確認後に残ったロックディレクトリを `rm -r ~/.config/orca-bot-remote/.orca-device-pairing` で削除します。

## 動作確認

```sh
launchctl print "gui/$(id -u)/dev.okumura.orca-bot-remote"
host=$(tailscale ip -4)
printf 'Authorization: Bearer %s\n' "$(cat ~/.config/orca-bot-remote/token)" |
  curl --noproxy '*' --fail-with-body --silent --show-error --header @- "http://$host:8787/api/bots"
```

Bot 一覧を受け取れたら、Android から同じ IP または MagicDNS 名とポート `8787`、同じトークンで接続します。会話履歴の確認には `/api/bots/{botName}/chats/{chatId}/messages?limit=10` を使います。`botName` には URL エンコードした Bot 名、`chatId` にはその Bot のチャット ID を指定します。API のパスと応答は [API](api.md) を参照してください。

標準出力は `~/Library/Logs/orca-bot-remote/access.log` に保存され、1リクエストにつきルートのテンプレート、HTTP ステータス、所要時間が1行で出ます。認証失敗時は `unauthenticated`、不明なルートは `unknown` と記録します。起動エラーは同じディレクトリの `error.log` に出ます。

## アプリの新しい版を置く

Mac mini でリポジトリのルートから実行します。

```sh
server/launchd/publish-app.sh
```

署名済みの APK と版情報が揃うと、`~/Library/Application Support/orca-bot-remote/app/` に公開し、版番号と SHA-256 を表示します。次にスマートフォンでアプリの設定を開くと、公開した版が表示されます。公開先のディレクトリはサーバーの設置先とは別にあり、サーバー更新後も使えます。

署名鍵と `signing.properties` はリポジトリ外の `~/.config/orca-bot-remote/release/` に置き、ファイルの権限を `600` に設定します。署名鍵を失うと、インストール済みアプリを上書き更新できなくなります。鍵の控えは本人が別の安全な場所に保管してください。

## 停止とアンインストール

一時停止する場合はジョブを登録解除します。再開は plist を再登録します。

```sh
launchctl bootout "gui/$(id -u)/dev.okumura.orca-bot-remote"
launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/dev.okumura.orca-bot-remote.plist"
```

アンインストールする場合は `server/launchd/uninstall.sh` を実行します。設置済みのサーバーと plist を削除します。トークン、端末資格情報、ログは再設置や調査に使えるよう残します。端末のアクセスを取り消す場合は Orca の設定で端末を削除します。

## 環境変数

| 変数 | 設定 |
| --- | --- |
| `HOST` | 必須。Tailscale IPv4 `100.64.0.0/10` または IPv6 `fd7a:115c:a1e0::/48` の IP アドレス。インストーラーは Mac mini の Tailscale IPv4 を設定します |
| `PORT` | 既定 `8787` |
| `TOKEN_FILE` | 既定 `~/.config/orca-bot-remote/token`。権限 `600` の通常ファイル。ファイルへのシンボリックリンクは拒否します |
| `ORCA_BIN` | 既定 `/Applications/Orca.app/Contents/Resources/bin/orca`。絶対パス |
| `APP_DIR` | 既定 `~/Library/Application Support/orca-bot-remote/app`。`install.sh` はこの値を plist に書き込まないため、通常は既定値を使います。上書きはテスト用です |
| `SUMMARY_AUTOMATION_ID` | まとめの自動化 ID。空の場合は `{"summary":null}` |
| `ORCA_TASKS_FILE` | 既定 `~/.orca/bots/bot/memory/tasks.json`（メインBotと共有するタスクリスト）。`install.sh` は plist に書き込みません。上書きはテスト用です |

サーバーは指定した Tailscale IP にだけバインドします。接続先は tailnet 内のサーバーを指定してください。
