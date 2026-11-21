# Orca Bot Remote

Mac mini 上で稼働する Orca の Bot を Android スマートフォンから操作するためのアプリです。

[開発環境と動作確認手順](docs/2026-11-21-phase0-devenv.md) · [サーバーの設置とペアリング](docs/server-setup.md) · [API](docs/api.md) · [学習メモ](docs/learning.md)

サーバーの E2EE と構造化セッションの操作 ID・fingerprint は、Orca 1.4.266 の配布物（コミット `3df243e7d5610fe956c7776144571c7d3640fadb`）にある MIT ライセンスのコードを使います。

- `server/vendor/orca/shared/remote-runtime/e2ee/` の `paired-e2ee-client-handshake.js`、`paired-e2ee-v2-client-session.js`、`mobile-e2ee-v2-contract.js`、`mobile-e2ee-v2-framing.js`、`node/e2ee-crypto.js`、`node/paired-e2ee-node-primitives.js`、`node/mobile-e2ee-v2-node-key-schedule.js`
- `server/vendor/orca/shared/` の `remote-runtime/pairing/device-key-proof.js`、`agent-session/lifecycle/operations/structured-agent-session-mutation.js`、`hashing/sha256.js`
- `server/test/fixtures/orca-protocol/` の `mobile-e2ee-v2-fixtures.cjs` と `paired-e2ee-fixed-key-test-fixture.cjs`。ESM のサーバーから読み込めるよう拡張子と固定鍵フィクスチャの参照先を調整しています。

[Orca のソース](https://github.com/stablyai/orca/tree/3df243e7d5610fe956c7776144571c7d3640fadb) · [MIT ライセンス](server/vendor/orca/LICENSE)

Android のマスコット20種とアプリアイコンも、同じ Orca 1.4.266 の配布物から取り込んでいます。配布物の `out/shared/bot-avatar.js` にある ID に対応する `out/renderer/assets/<ID>-<hash>.svg` を、ヘッドレス Chrome で読み込み、256 × 256 の Canvas に描画して PNG に書き出しました。SVG のぼかしなどの効果を残したまま `drawable-nodpi` に同梱しています。ランチャーは `resources/icon.png`、通知は `resources/tray/orca-menu-barTemplate@2x.png` の透明度マスクを白にして使用しています。[アプリ内の MIT ライセンス](android/app/src/main/assets/licenses/orca-LICENSE.txt)
