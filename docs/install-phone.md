# Pixel に Orca Bot Remote を入れる

## 1. Pixel で Tailscale をオンにする

1. Google Play ストアから **Tailscale** をインストールします。
2. Tailscale を開き、Mac mini と同じ Tailscale ネットワークに参加できるアカウントでログインします。
3. VPN 接続の確認が出たら許可します。Tailscale をオンにし、接続先の一覧に Mac mini が見えることを確認します。

[Tailscale の Android インストール手順](https://tailscale.com/docs/install/android)

## 2. APK を Pixel に移してインストールする

1. Mac でビルドした APK は `android/app/build/outputs/apk/debug/app-debug.apk` にあります。ビルドする場合は [開発環境と動作確認手順](2026-11-21-phase0-devenv.md#ビルドと画面確認)の `assembleDebug` を実行します。
2. APK を自分の Google Drive など、Pixel で開ける場所に置きます。Pixel でファイルをダウンロードし、**Files** アプリの「ダウンロード」から APK をタップします。
3. 提供元不明のアプリを許可する画面が出たら、**設定**をタップして、APK を開いたアプリの**この提供元のアプリを許可**をオンにします。戻って**インストール**をタップします。
4. インストール後は、Pixel の **設定 → アプリ → 特別なアプリアクセス → 不明なアプリのインストール**で、手順 3 で許可したアプリを選んで許可をオフに戻せます。

[Google Pixel ヘルプのインストール手順](https://support.google.com/pixelphone/answer/7391672?hl=ja)

## 3. Orca Bot Remote の接続先を設定する

1. Mac mini で Orca と中継サーバーが起動していることを確認します。Pixel で **Orca Bot Remote** を開きます。
2. **サーバー URL** に Mac mini のアドレスを入れます。例は `http://mac-mini:8787` です。MagicDNS 名が使えない場合は、Tailscale に表示される Mac mini の IP アドレスを `mac-mini` の代わりに入れます。IPv6 の場合はアドレスを `[` と `]` で囲みます。
3. **トークン**に中継サーバー用として受け取った値を入れ、**接続を確認して保存**をタップします。Android 17 以降でローカルネットワークへのアクセス許可を求められたら許可します。
4. 「接続できました。設定を保存しました」と表示され、Bot 一覧が開きます。「トークンが違います」と出たらトークンを確認します。「Mac mini につながりません」と出たら Tailscale の接続、URL、中継サーバーの起動を確認します。

[MagicDNS の説明](https://tailscale.com/docs/features/magicdns) · [Android のローカルネットワーク権限](https://developer.android.com/privacy-and-security/local-network-permission)

## 通知について

アプリは約15分ごとに Mac mini を確認します。通知が届くのはスマートフォンの Tailscale がオンで、Mac mini に接続できるときです。電池の最適化により、通知が遅れることがあります。
初回の確認では、すでにある判断待ちやまとめを記録します。その後に見つかった新しい内容を通知します。
アプリの「設定」で通知をオン・オフできます。Android 13 以降で通知の許可を断った場合は、「通知を許可する」から Android の設定を開けます。

## 通信設定について

アプリと Mac mini の間では、Tailscale の暗号化された接続の中で HTTP を使います。Android の通信設定 XML では `mac-mini` や `ts.net` のサブドメインを指定できますが、接続先として入力できる Tailscale の IP アドレスの範囲を指定できません。このためアプリ全体で平文 HTTP を許可しています。接続先には自分の Mac mini の Tailscale アドレスを入力してください。開発用のエミュレーター接続先 `10.0.2.2` への HTTP はデバッグ版に限って許可しています。

[Android のネットワークセキュリティ設定](https://developer.android.com/privacy-and-security/security-config)
