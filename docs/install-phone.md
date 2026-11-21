# Pixel に Orca Bot Remote を入れる

## 1. Pixel で Tailscale をオンにする

1. Google Play ストアから **Tailscale** をインストールします。
2. Tailscale を開き、Mac mini と同じ Tailscale ネットワークに参加できるアカウントでログインします。
3. VPN 接続の確認が出たら許可します。Tailscale をオンにし、接続先の一覧に Mac mini が見えることを確認します。

[Tailscale の Android インストール手順](https://tailscale.com/docs/install/android)

## 2. 初回だけリリース版 APK を Pixel に移してインストールする

1. Pixel にデバッグ版の Orca Bot Remote が入っている場合はアンインストールします。保存済みの URL とトークンは、リリース版のインストール後に入力し直します。
2. Mac でビルドした署名済み APK は `android/app/build/outputs/apk/release/app-release.apk` にあります。Mac mini で [アプリの新しい版を置く手順](server-setup.md#アプリの新しい版を置く)を実行すると、同じファイルが生成されます。
3. APK を自分の Google Drive など、Pixel で開ける場所に置きます。Pixel でファイルをダウンロードし、**Files** アプリの「ダウンロード」から APK をタップします。
4. 提供元不明のアプリを許可する画面が出たら、**設定**をタップして、APK を開いたアプリの**この提供元のアプリを許可**をオンにします。戻って**インストール**をタップします。
5. インストール後は、Pixel の **設定 → アプリ → 特別なアプリアクセス → 不明なアプリのインストール**で、手順 4 で許可したアプリを選んで許可をオフに戻せます。

[Google Pixel ヘルプのインストール手順](https://support.google.com/pixelphone/answer/7391672?hl=ja)

## 3. Orca Bot Remote の接続先を設定する

1. Mac mini で Orca と中継サーバーが起動していることを確認します。アプリの接続確認には、認証付きの `GET /api/health` に対応した中継サーバーが必要です。Pixel で **Orca Bot Remote** を開きます。
2. **サーバー URL** に Tailscale に表示される Mac mini の IP アドレス（例 `http://100.64.0.1:8787`）、または末尾が `.ts.net` の完全な MagicDNS 名（例 `http://mac-mini.example.ts.net:8787`）を入れます。IPv6 の場合はアドレスを `[` と `]` で囲みます。
3. **トークン**に中継サーバー用として受け取った値を入れ、**接続を確認して保存**をタップします。Android 17 以降でローカルネットワークへのアクセス許可を求められたら許可します。
4. 「接続できました。設定を保存しました」と表示され、Bot 一覧が開きます。「トークンが違います」と出たらトークンを確認します。「Mac mini につながりません」と出たら Tailscale の接続、URL、中継サーバーの起動を確認します。

[MagicDNS の説明](https://tailscale.com/docs/features/magicdns) · [Android のローカルネットワーク権限](https://developer.android.com/privacy-and-security/local-network-permission)

## 4. 2回目からはアプリの設定で更新する

1. Orca Bot Remote で **設定 → アプリの更新** を開きます。最新の版がいまの版より新しい場合は、**更新**をタップします。
2. 初回の更新で許可を求められたら、Orca Bot Remote の**この提供元のアプリを許可**をオンにします。アプリに戻り、もう一度**更新**をタップします。
3. Android の確認画面で**更新**をタップすると、インストールされます。

## 会話でコマンド・スキル・添付を使う

- 会話の入力欄に `/` を入れると、そのセッションで使えるコマンドとスキルが表示されます。`/model` と `/effort` では現在値を確認して変更できます。`/compact` は会話の圧縮、`/clear` は確認後のリセットです。
- スキルを選ぶと入力欄の上に名前が表示されます。指示を書いて送信してください。名前の横の × で外せます。入力欄に `/スキル名` が入る種類では、その後に指示を続けます。
- クリップボタンから画像やファイルを選べます。会話では最大10件・1件20 MiBまで、「新しく頼む」では最大5件・1件15 MiB・合計20 MiBまでです。添付だけでも送れます。送れたか確認できないと表示されたら、再送する前に会話を更新するか、新規チャットなら「チャット一覧で確かめる」をタップしてください。
- Bot の返信は Markdown の見出し・箇条書き・表などを表示します。Mermaid の図はタップで拡大できます。

## 通知について

アプリは約15分ごとに Mac mini を確認します。通知が届くのはスマートフォンの Tailscale がオンで、Mac mini に接続できるときです。電池の最適化により、通知が遅れることがあります。
初回の確認では、すでにある判断待ちやまとめを記録します。その後に見つかった新しい内容を通知します。
アプリの「設定」で通知をオン・オフできます。Android 13 以降で通知の許可を断った場合は、「通知を許可する」から Android の設定を開けます。

## 通信設定について

アプリは HTTP の接続先を Tailscale IPv4（`100.64.0.0/10`）、Tailscale IPv6（`fd7a:115c:a1e0::/48`、ゾーン ID なし）、および `.ts.net` で終わるホスト名に制限します。これまで `mac-mini` のような短い名前を保存していた場合は、設定でアドレスを入れ直してください。デバッグ版では開発用の `10.0.2.2`、`127.0.0.1`、`localhost` への HTTP も許可します。HTTPS の接続先には任意の有効なホスト名または IP アドレスを指定できます。

[Android のネットワークセキュリティ設定](https://developer.android.com/privacy-and-security/security-config)
