# Orca Bot Remote Phase 0 — 読み取りと本人としての書き込み

調査日 2026-11-21（JST）。Mac mini の Orca 1.4.266。配布物の `orca-local-build.json` にあるコミットは `3df243e7d5610fe956c7776144571c7d3640fadb`。タグ `v1.4.266` の参照先とは異なるため、ソースの根拠は配布物と同じコミットに揃えた。

## 1. 結論

**候補2の Orca WebSocket RPC を第一候補とする。** オーナーが正規にペアリングしたクライアントで、別チャットを開く `bots.session.start`、チャットへ入力する `agentSession.send`、判断待ちに答える `bots.items.owner.comment` を使う。既存チャットへはそのセッションIDを指定する。

「本人として送る」には三つの確認点がある。通常入力は `userSend: true` とジャーナルの `USER_MESSAGE_SOURCE` で確認する。送信元の来歴は `typedBy` で、ペア済みの非エージェント端末なら `device` と端末ID、ホスト画面なら `host`、CLIなら `agent` になる。端末とホストの両方に照合可能なアカウントがあり、IDが一致した場合に `own: true` が付く。Bot側のオーナー依頼の分類は通常の未スタンプ入力と会話の帰属で決まり、`typedBy.own` は参照しない。根拠と実測項目は第5節に記す。

採用の前提として、クライアントは接続時に `agent-session.structured.v1` を通知し、`clientOperationId` は `createStructuredAgentSessionOperationId` と同じ形式（13桁の時刻、ハイフン、32桁の小文字16進数）で生成する。Orcaへの接続はモバイル資格を第一候補とし、CLIでの発行はオーナー承認待ち。

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
- **モバイル直結のフレーム。** RPC 呼び出し側の基本形は `{id, method, params}`。直結では E2EE WebSocket を確立し、`rpc-envelope-fields.ts:19–41` と `direct-rpc-client.ts:298–301` で暗号化前に `deviceToken` を加えて送る。ホストは認証済み接続ならフレームごとのトークンを省略でき、接続の資格と異なるトークンが併記されていれば拒否する。RPC の権限には認証済みデバイスのスコープを使う（`runtime-rpc-websocket-dispatch.ts:156–184`）。
- **ペアリング付きCLI。** `src/cli/runtime/pairing/runtime-remote-pairing.ts` は `--pairing-code <pairing-code>` を解析するか、`--environment <id-or-name>` から保存済み接続を解決する。 `--environment local` での `bot list` は6件、`environment list --json` でのCLIの保存済み環境は0件。ペアリング付きCLIは `src/cli/runtime/connection/websocket-transport.ts` で `CLI_AGENT_CALLER_CLIENT_CAPABILITY` を通知し、`resolveRpcCallerIdentity` によりペア済みCLIエージェントとして扱われる。
- **試作。** Scratch の `/private/tmp/orca-bot-phase0.hCDd0u/read-rpc.cjs` は、展開した配布版の `RuntimeClient` を使用する。再実行時は `NODE_PATH=/Applications/Orca.app/Contents/Resources/node_modules` を指定し、引数に Bot ID とチャットのセッションIDを渡す。 `ORCA_*` を空にした環境で `status.get`、`agentSession.history`（tail、before）は成功。 `bots.items.owner.list` は `invalid_argument: Only your owner changes items this way, in Orca.` を返した。本人名義の試作送信は0通。

ソースは配布コミットの [Orca リポジトリ](https://github.com/stablyai/orca/tree/3df243e7d5610fe956c7776144571c7d3640fadb) とローカルの `app.asar` 展開物を照合した。`dev.orca.app` は調査環境から TLS 接続できなかったため、モバイルの呼び出しは当該コミットの実装で確認した。

## 5. 残る不確実性とオーナー確認

### 本人としての送信の判定

1. **通常のユーザー入力。** `agentSession.send` は `userSend: true` でホストに渡り、ジャーナルの `source` が `USER_MESSAGE_SOURCE`（`{kind:"user"}`）になる（`structured-agent-session-send-compatibility.ts:23–34`、`structured-agent-session-send-admission.ts:66–73`）。
2. **送信元の来歴。** 認証済みの非エージェント端末は `typedBy.by=device` と端末ID、ホストのデスクトップ画面からの入力は `host`、ローカル・ペア済みCLIは `agent`。端末側のアカウント情報とホストのサインイン済みアカウントIDの両方を照合できる場合に `own` が付き、一致で `true`、不一致で `false` になる。モバイル資格のアカウント情報は発行元端末の記録から解決する経路があり、照合情報が揃わない場合は `own` が省略される（`rpc-caller-typed-by.ts:19–44`、`paired-device-typist.ts:13–31`）。
3. **Botのオーナー依頼の分類。** 依頼者スタンプやメール由来の印がない通常入力は、Botがオーナーからの入力として扱う。分類は `typedBy.own` を参照しない。別人への代理が記録された会話では、会話が誰のために動くかも判定に効く（`bot-request-asker.ts:112–141, 173–200, 303–325`）。

遠隔クライアントには `agent-session.structured.v1` の通知が必要。公式モバイルは直結時に `runtime.clientCapabilities.update` で通知し、ホストはこの対応機能が欠けた structured chat の送信を拒否する（`mobile-runtime-client-capabilities.ts:32–39, 65–71`、`mobile-runtime-capability-negotiation.ts:11–19`、`structured-agent-session-gate.ts:21–40, 61–69`、`structured-agent-session-policy.ts:27–35, 71–80`）。

操作IDは `createStructuredAgentSessionOperationId` の形式（13桁の Unix 時刻のミリ秒値、ハイフン、32桁の小文字16進数）を使う。ホストは形式不正や許容範囲を超える未来時刻を拒否する（`structured-agent-session-mutation.ts:76–86`、`agent-session-host-authority.ts:40–49`、`agent-session-operation-ledger.ts:180–199`）。

### 接続とオーナーによる発行

**Android → 中継サーバー。** サーバーの待ち受けを Tailscale に限定し、Android からの要求を Bearer で認証する。

**中継サーバー → Orca。** Mac 上の中継サーバーが Orca に接続するための資格には、次の二つの候補がある。

| 候補 | オーナーが発行する操作と資格の範囲 | Orca の待ち受けへの影響 | 判断待ち回答の見込み |
| --- | --- | --- | --- |
| (A) モバイル資格 | ホストで `orca pair --mobile --address <Tailscaleアドレス>`。オーナー画面では Settings → Mobile → LAN → Generate QR code → Copy pairing code。 `mobile` スコープの許可リスト内の RPC を使える。 | LAN でも初回のネットワークペアリングは、loopback だった Orca の待ち受けを全 IPv4 インターフェースに広げる。 `--address` はクライアントに案内する接続先を指定する。 | `bots.items.owner.comment` はモバイルの許可リストにあり、非エージェント端末として認証されれば `ownerOnly` を通る見込み。実機のカード回答は未検証。 |
| (B) アクセスリンク＋loopback | `orca pair`（`--mobile` なし）は、モバイルの許可リストより広い `runtime` スコープのアクセスリンクを発行する。同じ Mac 内に限るリンクは、オーナー画面で Share this computer → This computer → Create link を選び、loopback に接続する。 | 画面の This computer で発行して接続先が loopback の場合、待ち受けを広げない。CLI の `orca pair` には This computer の指定がなく、通常のネットワーク用アクセスリンクの発行では初回に全 IPv4 インターフェースに広がる。 | 判定の鍵は非エージェント端末として認証されること。アクセスリンク＋loopback で `ownerOnly` を通るかは未検証。 |

`ownerOnly` はエージェント呼び出しを拒否する。許可されたペア済み端末からのカード回答は owner と端末IDを記録する（`bots-caller-gates.ts:5–10`、`bots-items-owner-methods.ts:33–38`、`runtime-rpc-mobile-bot-methods.ts:61–64`）。

11月21日にこの Mac で `lsof -nP -iTCP:6768 -sTCP:LISTEN` を実行し、Orca が既に IPv4 の `TCP *:6768 (LISTEN)` で待ち受けていることを確認した。広がった時期と他端末からの到達可否は未確認。発行と待ち受けの根拠は `orca pair --help`、`src/cli/server/pair.ts:54–77`、`runtime-rpc-mobile-pairing.ts:37–57, 141–167`、`runtime-rpc-network-exposure.ts:12–54`、`pairing-offer-requests.ts:43–49, 109–136, 172–181`。

### 採用前に実測する項目

- (A) を第一候補とし、オーナーの承認後に CLI で発行する。承認後の試験では通常入力1通についてジャーナルの `source` と `typedBy` を照合し、Botがオーナーの依頼として扱い、同じチャットに返答することを確認する。判断待ち1件について、回答イベントの発行とカードの状態更新を確認する。現時点の送信成功はワーカー名義の質問1通のみ。
- 実際の launchd 実行ユーザーと HOME で、起動後に読み取りが通るかは未検証。今回の結果は、同じユーザーの環境変数を消したプロセスでのもの。
- 11月22日12:00の初回予定以降に、定期実行の `chatSessionId` と `outputSnapshot.content` を照合する。長い履歴の全件ページングも、実データを使った確認は残っている。
