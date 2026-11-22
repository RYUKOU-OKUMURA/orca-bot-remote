# Orca Bot Remote

Mac で稼働する Orca の Bot を、Tailscale 経由で Android スマートフォンから操作するためのアプリと中継サーバーです。

```
Android アプリ ──(Tailscale)──▶ 中継サーバー（Mac・launchd 常駐）──▶ Orca
```

## 主な機能

- **Bot の一覧**: 稼働状況、未読、判断待ちの件数を確認
- **チャット**: 閲覧・送信・新規作成
  - 新規チャットでエージェント、モデル、推論強度を選択
  - 既存チャットでもモデル・推論強度をチップで切り替え
  - 画像・ファイルの添付、音声入力、スラッシュコマンドとスキル
  - Markdown 表示と Mermaid 図（タップで拡大）
- **判断待ちと通知**: 判断待ちへの回答、SSE によるリアルタイム通知、おやすみ時間、通知から直接返信
- **ホーム画面ウィジェット**
  - 秘書ボード: `タスク` / `進捗` タブの切り替え、稼働中の自動バナー、タスクの完了チェック
  - クイックメモ
- **アプリ内アップデート**: Mac 側で新しい APK を公開すると、アプリの設定画面から更新

## 必要な環境

| 場所 | 必要なもの |
| --- | --- |
| Mac | macOS（Mac mini 以外の Mac でも可）、Orca、Node.js 24 以上と npm、Tailscale |
| Android | Android 10（API 29）以降、Tailscale |

Mac と Android は同じ tailnet に参加させます。

## セットアップ

Mac では Orca にログインして起動しておき、同じ macOS ユーザーのターミナルでリポジトリのルートから作業します。

1. **中継サーバーを設置する**

   ```sh
   git clone https://github.com/RYUKOU-OKUMURA/orca-bot-remote.git
   cd orca-bot-remote
   server/launchd/install.sh
   ```

   初回に接続用トークンが `~/.config/orca-bot-remote/token`（権限 `600`）に作られます。

2. **Orca とペアリングする**（書き込みに必要）

   ```sh
   npm --prefix server ci
   node server/src/pair.ts
   ```

   実行前後で Orca の Settings → Mobile の端末一覧を控えると、サーバー用の端末を特定できます。

3. **Android にアプリを入れる**
   - GitHub の [Releases](https://github.com/RYUKOU-OKUMURA/orca-bot-remote/releases) に APK が公開されていれば、それをダウンロードしてインストール
   - または `android/` から自分でビルド（[開発環境](docs/2026-11-21-phase0-devenv.md)）

4. **アプリで接続する**: 設定画面の「サーバー URL」に `http://<Mac の Tailscale IP>:8787`（または `.ts.net` の MagicDNS 名）、「トークン」に手順 1 のトークンを入れて「接続を確認して保存」

詳しくは [サーバーの設置とペアリング](docs/server-setup.md) と [スマートフォンへの導入](docs/install-phone.md) を参照してください。

## 自分の環境で使うときの補足

- 手順書や画面の「Mac mini」は作者の環境での呼び名です。一般的な Mac でもそのまま動きます。
- 共有タスクリストは既定で `~/.orca/bots/bot/memory/tasks.json` を使います。`ORCA_TASKS_FILE` で変更できます。
- まとめの自動化は `SUMMARY_AUTOMATION_ID` で指定できます。未指定ならインストーラーが名前から探し、見つからなくても設置は続きます。
- 既定の Bot 名（`メインBot`）が無い環境では、先頭の Bot を使います。

## ドキュメント

- [サーバーの設置とペアリング](docs/server-setup.md)
- [スマートフォンへの導入](docs/install-phone.md)
- [API](docs/api.md)
- [開発環境と動作確認手順](docs/2026-11-21-phase0-devenv.md)
- [学習メモ](docs/learning.md)

## ライセンスと帰属表記

本リポジトリは [MIT ライセンス](LICENSE) です。

サーバーの E2EE と構造化セッションの操作 ID・fingerprint は、Orca 1.4.266 の配布物（コミット `3df243e7d5610fe956c7776144571c7d3640fadb`）にある MIT ライセンスのコードを使います。

- `server/vendor/orca/shared/remote-runtime/e2ee/` の `paired-e2ee-client-handshake.js`、`paired-e2ee-v2-client-session.js`、`mobile-e2ee-v2-contract.js`、`mobile-e2ee-v2-framing.js`、`node/e2ee-crypto.js`、`node/paired-e2ee-node-primitives.js`、`node/mobile-e2ee-v2-node-key-schedule.js`
- `server/vendor/orca/shared/` の `remote-runtime/pairing/device-key-proof.js`、`agent-session/lifecycle/operations/structured-agent-session-mutation.js`、`hashing/sha256.js`
- `server/test/fixtures/orca-protocol/` の `mobile-e2ee-v2-fixtures.cjs` と `paired-e2ee-fixed-key-test-fixture.cjs`。ESM のサーバーから読み込めるよう拡張子と固定鍵フィクスチャの参照先を調整しています。

[Orca のソース](https://github.com/stablyai/orca/tree/3df243e7d5610fe956c7776144571c7d3640fadb) · [MIT ライセンス](server/vendor/orca/LICENSE)

Android のマスコット20種とアプリアイコンも、同じ Orca 1.4.266 の配布物から取り込んでいます。配布物の `out/shared/bot-avatar.js` にある ID に対応する `out/renderer/assets/<ID>-<hash>.svg` を、ヘッドレス Chrome で読み込み、256 × 256 の Canvas に描画して PNG に書き出しました。SVG のぼかしなどの効果を残したまま `drawable-nodpi` に同梱しています。ランチャーは `resources/icon.png`、通知は `resources/tray/orca-menu-barTemplate@2x.png` の透明度マスクを白にして使用しています。[アプリ内の MIT ライセンス](android/app/src/main/assets/licenses/orca-LICENSE.txt)

Android アプリの図表示には [Mermaid](https://github.com/mermaid-js/mermaid)（MIT ライセンス）を `android/app/src/main/assets/mermaid/` に同梱しています。[Mermaid の MIT ライセンス](android/app/src/main/assets/licenses/mermaid-LICENSE.txt)
