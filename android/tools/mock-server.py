#!/usr/bin/env python3
"""Local fixtures for the Phase 1 Android emulator."""
import argparse
import hmac
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

TOKEN = "mock-orca-token"
BOT = "メインBot"
HOME_ID = "00000000-0000-4000-8000-000000000001"
REVIEW_ID = "00000000-0000-4000-8000-000000000002"
START_MS = int(time.time() * 1000)
SUMMARY = {
    "runAt": START_MS - 12 * 60_000,
    "content": (
        "# 午前の状況まとめ\n\n"
        "・ショート担当：次の動画の下書きができました。\n"
        "・GBP投稿：週末のお知らせは公開準備まで完了しました。\n"
        "・メール秘書：急ぎのメールはありません。\n\n"
        "## 確認してほしいこと\n"
        "週末のお知らせを、今日の夕方と明日の朝のどちらに公開するか決めてください。"
    ),
    "bot": BOT,
    "chatId": HOME_ID,
}
SPECIALTIES = {
    BOT: "全体の進行と相談",
    "ショート担当": "短い動画の企画",
    "GBP投稿": "Google マップのお知らせ",
    "整体院発信": "整体院のお知らせ",
    "メール秘書": "メールの整理",
    "文字起こし担当": "音声を文章に",
}
BOTS = [
    {
        "id": f"mock-bot-{index}",
        "name": name,
        "specialty": specialty,
        "paused": False,
        "needsYou": int(name == BOT),
    }
    for index, (name, specialty) in enumerate(SPECIALTIES.items(), 1)
]
CHATS = [
    {
        "id": REVIEW_ID,
        "title": "週末のお知らせ",
        "home": False,
        "lastActivityAt": START_MS - 60_000,
        "lastAsked": "公開する時間を相談したいです。",
        "lastSaid": None,
    },
    {
        "id": HOME_ID,
        "title": "メインBotとの会話",
        "home": True,
        "lastActivityAt": START_MS - 5 * 60_000,
        "lastAsked": "今日の進み具合を教えて",
        "lastSaid": "午前中の作業がまとまりました。確認が必要なのは週末のお知らせの公開時間だけです。",
    },
]
ITEM = {
    "bot": BOT,
    "number": 7,
    "title": "週末のお知らせの公開時間",
    "status": "needs_you",
    "chatId": REVIEW_ID,
}
MESSAGES = {
    HOME_ID: (
        "working",
        [
            {"kind": "message", "role": "user", "text": "今日の進み具合を教えて。先に確認しておくとよいことがあれば、理由も知りたいです。"},
            {"kind": "tool", "name": "状況の確認", "state": "done"},
            {
                "kind": "message",
                "role": "assistant",
                "text": (
                    "午前の作業がまとまりました。\n\n"
                    "ショート担当は次の動画の下書きを作成し、GBP投稿では週末のお知らせの準備が整いました。"
                    "メール秘書からは、今日中に返事が必要なメールはないと報告がありました。\n\n"
                    "確認が必要なのは週末のお知らせの公開時間です。今日の夕方なら早めに周知でき、"
                    "明日の朝なら開店前に見てもらいやすくなります。"
                ),
            },
        ],
    ),
    REVIEW_ID: (
        "done",
        [
            {"kind": "message", "role": "user", "text": "週末のお知らせを公開する時間を相談したいです。"},
            {"kind": "message", "role": "assistant", "text": "今日の夕方と明日の朝のどちらに公開しますか？"},
        ],
    ),
}


class MockHandler(BaseHTTPRequestHandler):
    def reply(self, status, data):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        authorization = self.headers.get("Authorization", "").encode("utf-8")
        if not hmac.compare_digest(authorization, f"Bearer {TOKEN}".encode("utf-8")):
            self.reply(401, {"error": "unauthorized"})
            return

        url = urlsplit(self.path)
        path = [unquote(part) for part in url.path.split("/")[1:]]
        match path:
            case ["api", "bots"]:
                data = {"bots": BOTS}
            case ["api", "bots", name, "chats"] if name in SPECIALTIES:
                data = {"chats": CHATS if name == BOT else []}
            case ["api", "bots", name, "chats", chat_id, "messages"] if name == BOT and chat_id in MESSAGES:
                state, entries = MESSAGES[chat_id]
                limit = parse_qs(url.query).get("limit", ["100"])[0]
                try:
                    limit = max(1, min(100, int(limit)))
                except ValueError:
                    limit = 100
                data = {"state": state, "entries": entries[-limit:]}
            case ["api", "items"]:
                data = {"items": [{**ITEM, "lastActivityAt": START_MS - 60_000}]}
            case ["api", "items", name, "7"] if name == BOT:
                data = {
                    **ITEM,
                    "question": {
                        "text": "週末のお知らせは下書きができています。いつ公開しますか？",
                        "options": ["今日の18:00に公開する", "明日の9:00に公開する"],
                    },
                }
            case ["api", "summary"]:
                data = {"summary": SUMMARY}
            case _:
                self.reply(404, {"error": "not_found", "message": "対象が見つかりません。"})
                return
        self.reply(200, data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8787)
    options = parser.parse_args()
    with ThreadingHTTPServer(("127.0.0.1", options.port), MockHandler) as server:
        print(f"Mock API listening on 127.0.0.1:{server.server_port}", flush=True)
        server.serve_forever()
