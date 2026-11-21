# Orca Bot Remote Phase 0 — 読み取りと本人としての書き込み

調査日 2026-11-21（JST）。Mac mini の Orca 1.4.266。配布物の `orca-local-build.json` にあるコミットは `3df243e7d5610fe956c7776144571c7d3640fadb`。タグ `v1.4.266` の参照先とは異なるため、ソースの根拠は配布物と同じコミットに揃えた。

## 1. 結論

**候補2。本人として送る経路は、オーナーが正規にペアリングしたクライアントによる Orca の WebSocket RPC。** メインBotの別チャットを開く `bots.session.start`、チャットの入力を送る `agentSession.send`、判断待ちのボタンに答える `bots.items.owner.comment` が該当する。既存チャットへの通常入力は、そのチャットのセッションIDを指定した `agentSession.send` で行う設計。

現時点で Mac mini のセッション外プロセスから実測できたのは読み取りまで。候補1の `orchestration send` は送信元の生きた Orca セッションを要求し、届いてもワーカーからのメールとして扱われる。ローカルランタイムソケットは CLI をエージェントとして識別し、オーナー用の items RPC を拒否した。候補2のサーバー採用可否は、正規にペアリングしたクライアントで本人入力とカード回答を実測するまで保留。

## 2. 読む口

以下の `clean_orca` は普段の Orca セッション変数を外し、同じ macOS ユーザーの HOME と配布版 CLI を使う。起動済みの Orca を対象とした。実際の launchd ジョブは作成していない。

```sh
clean_orca() {
  env -i HOME="$HOME" PATH=/Applications/Orca.app/Contents/Resources/bin:/usr/bin:/bin orca "$@"
}
```

成功時の CLI JSON は原則 `id`、`ok`、`result`、`_meta.runtimeId` を持つ。表のフィールドは `result` 以下。

| コマンド | セッション外の実測と主要フィールド |
| --- | --- |
| `bot list` / `bot list --json` | 両形式で成功。JSON は `bots[]` と `unreachable`。Bot 行の主要フィールドは `id`、`name`、`agent`、`specialty`、`paused`、`linked`、`chats`。6件。 |
| `bot chats --bot メインBot --json` | 成功。 `bot` は名前文字列、`chats[]` は `id`、`title`、`home`、`closed`、`self`、`lastActivityAt`（epoch ms）、`lastAsked`、`lastSaid`。試験前は10件、`--all` 付きで11件。プレビューは空の場合がある。 |
| `agent read orca_session_id:<chat_id> --limit 100 --json` | 成功。 `orcaSessionId`、`agent`、`kind`、`source`、`state`、`limited`、`entries[]`。観測した行はメッセージの `{kind, role, text}` とツールの `{kind, name, state}`。既存チャット `533ca387-abc1-45cb-badb-6abbb58dd5a4` は56行、`limited: false`。 |
| `bot item list --bot <name> --status needs-you --json` | 全6 Botで成功し、各 `items: []`。 `botId`、`botName`、`items[]`、`counts`。`counts` は指定ステータス以外も含む。メインBotを `--all` で読むと3件。行には `uid`、`number`、`title`、`status`、`chat`、`question`、`snoozedUntil`、`lastActivityAt`、`lastEventId` 等がある。ヘルプ上は1ページ50件、`--limit` で最大100件。 |
| `bot item show <number> --json` | メインBotの #7 で成功。 `item.chat` は `{id, kind, title}`、`events[]` は `{id, kind, actor, at, data}`。判断待ちの行があるときは `item.question.eventId` と一致するイベントから質問・選択肢を取得する設計。 |
| `orchestration gate-list --json` | 送信元なしでは `no_active_sender_terminal`。オーケストレーションの Run／Task の decision gate を列挙する。 |
| `orchestration inbox --limit 100 --json` | 成功。 `messages[]` に `id`、`from`、`to`、`type`、`subject`、`body`。 `--terminal` を省略すると複数のメールボックスを対象とする。 |
| `orchestration request-show --request <id> --json` | 成功。 `requestId`、`state`、`interpretation`。ワーカー名義の失敗した送信で出た ID をセッション外から照会したところ `state: absent`。照会は送信リクエストの処理結果に使う。 |

### 判断待ちと全履歴

`bot item --help` は `Unknown command: bot item`。 `bot --help` と `agent-context --json` の公開一覧にも items 系が出ない。一方、`bot item list --help` と実際の `bot item list --bot <name> --status needs-you --json` は成功した。配布コミットの `src/cli/specs/bot-items.ts` では各サブコマンドが `hidden: true`。 `src/cli/bots/bot-items.ts` のリスト処理は `bots.items.list` を呼ぶ。アプリの Needs you 一覧にはこの CLI を使い、`items[]` の `status`、`snoozedUntil` と質問イベントを表示に使う。

`agent read` は最新100行が上限で、過去へ送るカーソルがない。100行を超える全履歴にはローカル IPC の `agentSession.history` が使える。セッション外の試作で既存チャットに `{sessionId, direction:"tail", limit:3}` を送り、`result.page.items` が3行、`hasOlder: true` を確認した。続いて `direction:"before"` と `cursor:page.window.oldest` で過去の3行を取得した。過去ページの継続には `window.nextCursor` と `hasOlder` を使う。ここでの検証は2ページまで。

### 毎日12:00のまとめ

`automations list --json` で `専門Botの状況まとめ（毎日12:00）` を確認した。定義 ID は `c302b577-eaad-43f2-b09a-81650b386cf3`。 `enabled: true`、`timezone: "Asia/Tokyo"`、`rrule: "FREQ=DAILY;BYHOUR=12;BYMINUTE=0"`、`bot.runsIn: "chat"`、`bot.resultsTo: "chat"`。 `bot.botId` はメインBotと一致し、明示的な `bot.chat.sessionId` は設定されていない。次回は11月22日12:00 JST。11月21日の調査中に `automations runs --id c302b577-eaad-43f2-b09a-81650b386cf3 --limit 10 --json` の `result` は `{runs:[], nextCursor:null}` だった。

現行設定はメインBotの Home Chat（調査時は `aa14cad0-8d57-485f-80b7-df7c8b29c7cf`）に向かう。Home の履歴には設定時のやり取りがあった。実行済みのまとめは未確認。 `src/main/automations/bot-automation-launch.ts` の `createBotAutomationDispatcher` は、この設定で `sendToChat` を使い、実行行に `chatSessionId` を返す。アプリは設定のIDで履歴を絞り、`trigger: "scheduled"`、`scheduledFor`、`status: "completed"` で対象回を識別する。本文は `outputSnapshot.content`、遷移先は `chatSessionId` を使う設計。両フィールドは nullable で、この自動化の初回実行後に実値を照合する。

## 3. 候補1 — 送信試験

依頼文は全試行で共通とし、返事は「受信テストOK」だけを求めた。件名は全試行で `[テスト] orca-bot-remote 書き込み試験` から始めた。

```sh
kind=question
clean_orca orchestration send --to bot:メインBot --chat new --type "$kind" \
  --subject "[テスト] orca-bot-remote 書き込み試験 $kind" \
  --body 'このメッセージを受け取ったら「受信テストOK」とだけ返してください。' --json
```

`kind` に `question`、`handoff`、`status` をそれぞれ指定した。 `--from` なしでは3試行とも終了コード1、チャット一覧の差分なし。JSON と標準エラーは3回とも以下の全文だった。

```text
{"ok":false,"error":{"code":"no_active_sender_terminal","message":"Could not determine the calling terminal for this orchestration command. Pass --from with your own terminal's handle — another pane's handle would act on its mailbox — or run the command inside a live Orca terminal with ORCA_TERMINAL_HANDLE set."}}
error: no_active_sender_terminal: Could not determine the calling terminal for this orchestration command. Pass --from with your own terminal's handle — another pane's handle would act on its mailbox — or run the command inside a live Orca terminal with ORCA_TERMINAL_HANDLE set.
```

比較用に、同じ `orca orchestration send` を調査ワーカー自身の `--from <worker-terminal>` 付きで実行した。件名末尾に「（worker）」を追加した。

| 種別 | ワーカー自身を送信元にした実測 |
| --- | --- |
| `question` | 1通が新規チャット `7013b16c-f51e-4e11-8584-c6908ddeea24` に届いた。送信結果は `messageId: "msg_d23e58c5f500"` と返信をメールで受ける旨の `next`。 |
| `handoff` | 終了コード1、`runtime_error`。本文の要点は “Giving the job away is the call of the bot that started you…” 。このワーカーに引き継ぎの権限がなく、送信されなかった。ヘルプ上は、許可された送信元が仕事を譲るための種別。 |
| `status` | 終了コード1、`invalid_argument`。 `--chat new` は `handoff` と `question` 専用。ヘルプ上の `status` は情報提供で、返答先を作らない。既存チャットへの送信試験は行っていない。 |

`agent read orca_session_id:7013b16c-f51e-4e11-8584-c6908ddeea24 --limit 20 --json` では、依頼は `role: "user"` の行に入ったものの、冒頭は `From: … (question)` と `Subject:`。続く `botRequest` は `from: "メインBot's worker"`、`for: "your owner"` とメール返信の案内を持っていた。チャット内の最終文は `NO_REPLY`、一覧の `lastSaid` は null。 `orchestration inbox --limit 100 --json` で照合したところ、返信 `msg_50aa4848dedc` の本文は「受信テストOK」で、宛先はワーカーの `dispatch:ctx_f880ad0bf318`。ターミナルハンドルで絞ったメールボックスは空だった。Bot はこの無害な質問に応じたが、送達記録と返信先はワーカーとのメール往復を示す。オーナーがチャットへ直接入力した場合の挙動までは示さない。

`--item <n>` は実送信なし。全6 Botで Needs you が0件で、項目指定と `--chat new` は排他的。 `src/main/runtime/rpc/methods/orchestration/messaging/bot-chat-address.ts` の `chatForItem` は、項目の所有Botを検査し、所有チャット（消失時は Home）をメール宛先として返す。カードの回答記録や状態更新は行わない。

## 4. 候補2 — 公式クライアントとローカル IPC

### メッセージと判断待ち

公式モバイルクライアントは、`mobile/src/bots/bot-sheet-operations.ts` の `botSessionStart` から `bots.session.start({botId})` を呼んで `sessionId` を得る。ホストの実装は `src/main/runtime/rpc/methods/bots.ts`。この RPC は同一Bot・同一エージェントの空チャットが開いていれば再利用するため、新規チャット限定の試験では送信前に既存IDとの照合が必要。 `bots.chat.open` は Home を開く経路。

本文は `mobile/src/session/native-chat/agent-session/mobile-structured-agent-session-send.ts` の `sendMobileStructuredAgentSessionMessage` が `agentSession.send` を呼ぶ。文字だけなら RPC の `params` は次の形になる。

```json
{
  "envelope": {
    "sessionId": "<chat-id>",
    "clientOperationId": "<operation-id>",
    "expectedRuntimeFence": 1,
    "payloadFingerprint": "<fingerprint>"
  },
  "body": {
    "kind": "message",
    "role": "user",
    "blocks": [{ "type": "text", "text": "<依頼本文>" }]
  }
}
```

`expectedRuntimeFence` には履歴が返す `page.fence` の数値を入れる。 `payloadFingerprint` は `src/shared/agent-session/lifecycle/operations/structured-agent-session-mutation.ts` の `structuredAgentSessionPayloadFingerprint` で、`{method, sessionId, fields:{body}}` を正規化して SHA-256 を16進化する。 `mobile-structured-agent-session-rpc.ts` の `requestStructuredAgentSessionMutation` がこの envelope を組む。同じ操作の再照合には操作IDを保持する。ホストの `sendStructuredAgentSessionForClient` は入力の `role` に加え、`typedByOf` で認証済みトランスポートの来歴を付ける。

配布コミットの判断待ちボタンは `mobile/src/bots/items/MobileBotItemQuestionLines.tsx` で `botItemQuickReplyText` を使い、`mobile/src/bots/items/bot-item-operations.ts` から `bots.items.owner.comment({item:"<uid>", text:"<選択肢のラベル>"})` を呼ぶ。複数の質問があるとき、同じヘルパーが質問の1行要約とラベルを `質問 → ラベル` にする。 `bots.items.owner.list({botId, chatId?})` と `bots.items.owner.show({item, newest:true})` がオーナー画面の読み取り。 `src/main/runtime/rpc/methods/bots-items-owner-methods.ts` のコメント処理は `ownerActor` で認証を確認し、`{eventId}` を返す。 `src/main/bots/bot-items-owner.ts` の `ownerComment` は質問に回答を関連付け、 `needs_you` から `working`（UIの In progress）へ戻し、所有チャットへの通知を予約する。実機のカード回答は未実施。チャット内に出るエージェントの承認・質問プロンプトは別の機能で、同じモバイルコード上では `agentSession.currentRequests` と `agentSession.respondToApproval` / `agentSession.respondToQuestion` が担当する。

### 認証と試作

- **ローカル IPC。** `src/cli/runtime/profile/metadata.ts` と `src/cli/runtime/connection/transport.ts` は、当該 macOS ユーザーの Orca プロファイルのランタイム情報にある Unix ソケットへ接続する。RPC は `{id, authToken, method, params}` を改行区切りの JSON で送る。 `src/main/runtime/rpc/server/runtime-rpc-request-admission.ts` がローカルの正規トークンを照合し、呼び出し元を `local-cli` として判定する。 `src/main/runtime/rpc/callers/rpc-caller-typed-by.ts` の `typedByOf` は `local-cli` を `{by:"agent"}` と記録する。オーナー専用の items RPC は `bots-caller-gates.ts` の `ownerOnly` により拒否される。
- **ペアリング。** `src/cli/runtime/pairing/runtime-remote-pairing.ts` は `--pairing-code <pairing-code>` を解析するか、`--environment <id-or-name>` から保存済み接続を解決する。 `--environment local` での `bot list` は6件返った。保存済み環境は `environment list --json` で0件。これはCLIの保存済み環境の件数。正規のモバイル接続は `mobile/src/transport/rpc/direct-socket-framing.ts` で E2EE WebSocket を確立してデバイストークンで認証し、`{id, method, params}` 形式の RPC を送る。サーバーは `runtime-rpc-websocket-dispatch.ts` で認証結果のデバイスとスコープを採用する。一方、ペアリング付きCLIは `src/cli/runtime/connection/websocket-transport.ts` で `CLI_AGENT_CALLER_CLIENT_CAPABILITY` を送る。 `resolveRpcCallerIdentity` はこれをペア済みCLIエージェントと識別するため、ペアリングフラグを付けるだけでは本人操作にならない。
- **試作。** Scratch の `/private/tmp/orca-bot-phase0.hCDd0u/read-rpc.cjs` は、展開した配布版の `RuntimeClient` を使用。 `ORCA_*` を空にした環境で `status.get`、`agentSession.history`（tail、before）は成功。 `bots.items.owner.list` は `invalid_argument: Only your owner changes items this way, in Orca.` を返した。承認済みの試験用ペアリングが今回の作業範囲にないため、本人名義の試作送信は0通。生トークンやペアリングコードは文書と試作コードに保存していない。

ソースは配布コミットの [Orca リポジトリ](https://github.com/stablyai/orca/tree/3df243e7d5610fe956c7776144571c7d3640fadb) とローカルの `app.asar` 展開物を照合した。`dev.orca.app` は調査環境から TLS 接続できなかったため、モバイルの呼び出しは当該コミットの実装で確認した。

## 5. 残る不確実性とオーナー確認

- サーバー用の正規ペアリングをオーナーが許可した場合に、試験専用クライアントで本人入力1通と判断待ち1件を確認する。入力の来歴、同じチャットに返答が載ること、カードのイベントと状態が変わることを確認できれば、候補2を採用判断できる。現時点の送信成功はワーカー名義の質問1通のみ。
- 実際の launchd 実行ユーザーと HOME で、起動後に読み取りが通るかは未検証。今回の結果は、同じユーザーの環境変数を消したプロセスでのもの。
- 11月22日12:00の初回予定以降に、定期実行の `chatSessionId` と `outputSnapshot.content` を照合する。項目回答と長い履歴の全件ページングも、実データを使った確認は残っている。
