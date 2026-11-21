#!/usr/bin/env python3
"""Local fixtures for the Orca Remote Android emulator."""
import argparse
import base64
import hmac
import json
import mimetypes
import time
from threading import Lock
from uuid import uuid4
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

TOKEN = "mock-orca-token"
# Match the server's JavaScript trim() when counting message characters.
JS_WHITESPACE = "\t\n\v\f\r \xa0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"
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
BOT_FIXTURES = {
    BOT: ("全体の進行と相談", "A1"),
    "文字起こし担当": ("音声を文章に", "T1"),
    "GBP投稿": ("Google マップのお知らせ", "O1"),
    "整体院発信": ("整体院のお知らせ", "L2"),
    "メール秘書": ("メールの整理", "P3"),
    "ショート担当": ("短い動画の企画", "N1"),
}
BOTS = [
    {
        "id": f"mock-bot-{index}",
        "name": name,
        "specialty": specialty,
        "avatar": f"mascot:{mascot}",
        "paused": False,
        "needsYou": int(name == BOT),
    }
    for index, (name, (specialty, mascot)) in enumerate(BOT_FIXTURES.items(), 1)
]
CHATS = {BOT: [
    {
        "id": REVIEW_ID,
        "title": "週末のお知らせ",
        "home": False,
        "lastActivityAt": START_MS - 60_000,
        "lastAsked": "公開する時間を相談したいです。",
        "lastSaid": "今日の夕方と明日の朝のどちらに公開しますか？",
    },
    {
        "id": HOME_ID,
        "title": "メインBotとの会話",
        "home": True,
        "lastActivityAt": START_MS - 5 * 60_000,
        "lastAsked": "今日の進み具合を教えて",
        "lastSaid": "午前中の作業がまとまりました。確認が必要なのは週末のお知らせの公開時間だけです。",
    },
]}
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

for number, (name, hours, preview) in enumerate((
    ("文字起こし担当", 6, "前編と後編をまとめた要約を作りました。確認をお願いします。"),
    ("GBP投稿", 19, "週末のお知らせを用意しました。公開時間の確認をお願いします。"),
    ("整体院発信", 22, "前の担当から引き継ぎ、来週のお知らせを下書きにしました。"),
    ("メール秘書", 23, "宣伝メールを整理しました。急ぎの返信が必要なメールはありません。"),
    ("ショート担当", 7, "「会話が長くなると噛み合わなくなる理由」の構成案を作りました。"),
), 3):
    chat_id = f"00000000-0000-4000-8000-{number:012d}"
    CHATS[name] = [{
        "id": chat_id, "title": None, "home": True,
        "lastActivityAt": START_MS - hours * 3_600_000, "lastSaid": preview,
    }]
    MESSAGES[chat_id] = ("done", [{"kind": "message", "role": "assistant", "text": preview}])

TRANSCRIPT_ID = "00000000-0000-4000-8000-000000000008"
TRANSCRIPT_REPLY = "前編と後編をまとめた要約を作りました。確認をお願いします。"
CHATS["文字起こし担当"].append({
    "id": TRANSCRIPT_ID, "title": "YouTube 立ち上げ秘話の文字起こし", "home": False,
    "lastActivityAt": CHATS["文字起こし担当"][0]["lastActivityAt"], "lastSaid": TRANSCRIPT_REPLY,
})
MESSAGES[TRANSCRIPT_ID] = ("done", [{"kind": "message", "role": "assistant", "text": TRANSCRIPT_REPLY}])

LOCK = Lock()
WORKING_UNTIL = {}


MIB = 1024 * 1024
COMMANDS = [
    {"name": "clear", "kind": "conversation", "description": "会話をリセット"},
    {"name": "compact", "kind": "conversation", "description": "会話を圧縮", "argumentHint": "[指示]"},
    {"name": "model", "kind": "option", "description": "モデルを変更"},
    {"name": "effort", "kind": "option", "description": "推論強度を変更"},
    {"name": "help", "kind": "text", "description": "ヘルプを表示"},
]
SKILLS = [{"name": "review", "description": "変更をレビュー", "path": "/skills/review/SKILL.md"},
          {"name": "notes", "description": "ノートを書く", "path": None}]
CHOICES = {"model": [("standard", "Standard"), ("fast", "Fast")],
           "effort": [("low", "低"), ("high", "高")]}
OPTIONS = {}
UPLOADS = {}


def chat_state(chat_id):
    if chat_id in WORKING_UNTIL:
        return "working" if time.monotonic() < WORKING_UNTIL[chat_id] else "done"
    return MESSAGES[chat_id][0]


def chat_options(chat_id):
    selected = OPTIONS.get(chat_id, {})
    return {key: {"current": selected.get(key, choices[0][0]),
                  "choices": [{"value": v, "label": label} for v, label in choices]}
            for key, choices in CHOICES.items()}


def attachment_names(body, operation, chat_id, json_bytes):
    if operation == "start":
        files = body.get("inlineAttachments", [])
        if not isinstance(files, list) or len(files) > 5:
            raise ValueError()
        total = 0
        names = []
        for file in files:
            if not isinstance(file, dict) or not isinstance(file.get("name"), str) or not file["name"]:
                raise ValueError()
            encoded = file.get("contentBase64")
            if not isinstance(encoded, str):
                raise ValueError()
            size = len(base64.b64decode(encoded, validate=True))
            total += size
            if not 0 < size <= 15 * MIB or total > 20 * MIB:
                raise ValueError()
            json_bytes -= len(encoded)
            names.append(file["name"])
        if json_bytes > 64 * 1024:
            raise ValueError()
        return names
    files = body.get("attachments", [])
    skills = body.get("skills", [])
    if not isinstance(files, list) or len(files) > 10 or not isinstance(skills, list) or len(skills) > 16:
        raise ValueError()
    for skill in skills:
        if not isinstance(skill, dict) or not any(
            ref["path"] and ref["path"] == skill.get("path") and ref["name"] == skill.get("name") for ref in SKILLS
        ):
            raise ValueError()
    names = []
    for file in files:
        if not isinstance(file, dict) or not isinstance(file.get("path"), str):
            raise ValueError()
        ref = UPLOADS.get((chat_id, file["path"]))
        if not ref or file.get("kind") != ref["kind"]:
            raise ValueError()
        names.append(ref["name"])
    return names


class MockHandler(BaseHTTPRequestHandler):
    def reply(self, status, data, allow=None):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8", "backslashreplace")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        if allow:
            self.send_header("Allow", allow)
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        authorization = self.headers.get("Authorization", "").encode("utf-8")
        if hmac.compare_digest(authorization, f"Bearer {TOKEN}".encode("utf-8")):
            return True
        self.reply(401, {"error": "unauthorized"})
        return False

    def method_not_allowed(self, allowed):
        self.reply(405, {"error": "bad_request"}, allow=allowed)

    def do_GET(self):
        if not self.authorized():
            return
        url = urlsplit(self.path)
        path = [unquote(part) for part in url.path.split("/")[1:]]
        with LOCK:
            match path:
                case ["api", "health"]:
                    data = {"ok": True}
                case ["api", "bots"]:
                    data = {"bots": []}
                    for bot in BOTS:
                        latest = max(CHATS.get(bot["name"], []), key=lambda chat: chat["lastActivityAt"], default={})
                        working = any(chat_state(chat["id"]) == "working" for chat in CHATS.get(bot["name"], []))
                        data["bots"].append({
                            "working": working, "activeWork": "状況を確認中" if working else None,
                            **bot,
                            "lastActivityAt": latest.get("lastActivityAt"),
                            "lastSaid": latest.get("lastSaid"),
                        })
                case ["api", "bots", name, "chats"] if name in BOT_FIXTURES:
                    data = {"chats": sorted(
                        (dict(chat, working=chat_state(chat["id"]) == "working") for chat in CHATS.get(name, [])),
                        key=lambda chat: chat.get("lastActivityAt", 0), reverse=True
                    )}
                case ["api", "bots", name, "chats", chat_id, "messages"] if any(
                    chat["id"] == chat_id for chat in CHATS.get(name, [])
                ):
                    state, entries = chat_state(chat_id), MESSAGES[chat_id][1]
                    limit = parse_qs(url.query).get("limit", ["100"])[0]
                    try:
                        limit = max(1, min(100, int(limit)))
                    except ValueError:
                        limit = 100
                    workers = [{"title": "進捗確認", "tool": "read"}] if state == "working" else []
                    data = {"state": state, "entries": entries[-limit:], "activeWorkers": workers}
                case ["api", "bots", name, "chats", chat_id, "commands"] if any(
                    chat["id"] == chat_id for chat in CHATS.get(name, [])
                ):
                    if self.server.write_failure == "503":
                        self.reply(503, {"error": "write_unavailable"})
                        return
                    data = {"commands": COMMANDS, "skills": SKILLS, "options": chat_options(chat_id)}
                case ["api", "bots", _, "chats", _, "command" | "attachments"]:
                    self.method_not_allowed("POST")
                    return
                case ["api", "items"]:
                    data = {"items": (
                        [{**ITEM, "lastActivityAt": START_MS - 60_000}]
                        if ITEM["status"] == "needs_you" else []
                    )}
                case ["api", "items", name, "7"] if name == BOT:
                    data = {
                        **ITEM,
                        "question": {
                            "text": "週末のお知らせは下書きができています。いつ公開しますか？",
                            "options": ["今日の18:00に公開する", "明日の9:00に公開する"],
                        } if ITEM["status"] == "needs_you" else None,
                    }
                case ["api", "summary"]:
                    data = {"summary": SUMMARY}
                case ["api", "items", _, _, "answer"]:
                    self.method_not_allowed("POST")
                    return
                case _:
                    self.reply(404, {"error": "not_found", "message": "対象が見つかりません。"})
                    return
            self.reply(200, data)

    def upload_size(self, length):
        if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
            if not 0 < length <= 20 * MIB or len(self.rfile.read(length)) != length:
                raise ValueError()
            return length
        size = 0
        while True:
            count = int(self.rfile.readline(128).split(b";", 1)[0].strip(), 16)
            if count < 0 or count > 20 * MIB - size:
                raise ValueError()
            if len(self.rfile.read(count)) != count or self.rfile.read(2) != b"\r\n":
                raise ValueError()
            if not count:
                if not size:
                    raise ValueError()
                return size
            size += count

    def do_POST(self):
        if not self.authorized():
            return
        path = [unquote(part) for part in urlsplit(self.path).path.split("/")[1:]]
        match path:
            case ["api", "bots", name, "chats"]:
                operation = "start"
            case ["api", "bots", name, "chats", chat_id, "messages" | "command" | "attachments" as operation]:
                pass
            case ["api", "bots", _, "chats", _, "commands"]:
                self.method_not_allowed("GET")
                return
            case ["api", "items", name, "7", "answer"] if name == BOT:
                operation = "answer"
                chat_id = REVIEW_ID
            case ["api", "bots" | "items" | "summary"] | ["api", "items", _, _]:
                self.method_not_allowed("GET")
                return
            case _:
                self.reply(404, {"error": "not_found", "message": "対象が見つかりません。"})
                return
        if name not in BOT_FIXTURES:
            self.reply(404, {"error": "not_found", "message": "対象が見つかりません。"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if operation == "attachments":
                file_name = unquote(self.headers.get("X-File-Name", ""), errors="strict")
                if not file_name.strip() or len(file_name.encode("utf-16-le")) > 512 or content_type.startswith("multipart/"):
                    raise ValueError()
                length = self.upload_size(length)
            else:
                limit = 28 * MIB if operation == "start" else 64 * 1024
                if content_type != "application/json" or not 0 < length <= limit:
                    raise ValueError()
                body = json.loads(self.rfile.read(length).decode("utf-8-sig"))
                if not isinstance(body, dict):
                    raise ValueError()
                if operation == "command":
                    command = body.get("name")
                    arg = body.get("argument", "")
                    if (not isinstance(arg, str) or
                        not (command == "clear" and not arg or command == "compact" and len(arg) <= 10000 or
                             isinstance(command, str) and arg in dict(CHOICES.get(command, [])))):
                        raise ValueError()
                else:
                    names = []
                    if operation in ("start", "messages"):
                        with LOCK:
                            names = attachment_names(body, operation, chat_id if operation == "messages" else None, length)
                    message = body.get("text", "")
                    if not isinstance(message, str):
                        raise ValueError()
                    trimmed = message.strip(JS_WHITESPACE)
                    if (not trimmed and not names) or len(trimmed) > 10000:
                        raise ValueError()
                    if operation == "answer" and len(message.encode("utf-16-le", "surrogatepass")) // 2 > 8000:
                        raise ValueError()
                    if names:
                        message = (message + "\n" + " ".join("@" + n for n in names)).strip()
                        trimmed = message.strip(JS_WHITESPACE)
        except ValueError:
            self.reply(400, {"error": "bad_request", "message": "入力内容を確認してください。"})
            return

        time.sleep(self.server.write_delay)
        failure = self.server.write_failure
        with LOCK:
            if failure == "503":
                self.reply(503, {"error": "write_unavailable"})
                return
            if failure == "409":
                if operation == "answer":
                    finish_item()
                self.reply(409, {"error": "conflict", "message": "状態が変わりました。"})
                return
            if failure == "502":
                self.reply(502, {"error": "orca_failed", "message": "Orca に送れませんでした。"})
                return
            if operation == "start":
                chat_id = str(uuid4())
                CHATS.setdefault(name, []).insert(0, {
                    "id": chat_id,
                    "title": trimmed.splitlines()[0][:60],
                    "home": False,
                })
                MESSAGES[chat_id] = ("done", [])
            chat = next((chat for chat in CHATS.get(name, []) if chat["id"] == chat_id), None)
            if not chat:
                self.reply(404, {"error": "not_found", "message": "対象が見つかりません。"})
                return
            if operation == "answer" and ITEM["status"] != "needs_you":
                self.reply(409, {"error": "conflict", "message": "状態が変わりました。"})
                return

            if operation == "attachments":
                path = f"/mock-store/{chat_id}/{uuid4()}/{file_name}"
                mime = mimetypes.guess_type(file_name)[0] or ""
                kind = "image" if content_type.startswith("image/") or mime.startswith("image/") else "file"
                ref = {"path": path, "name": file_name, "kind": kind, "byteLength": length}
                UPLOADS[chat_id, path] = ref
                if failure != "unknown_result":
                    self.reply(201, ref)
                    return
            elif operation == "command":
                if command == "clear":
                    MESSAGES[chat_id][1].clear()
                elif command == "compact":
                    MESSAGES[chat_id][1].append({"kind": "tool", "name": "会話を圧縮", "state": "done"})
                else:
                    OPTIONS.setdefault(chat_id, {})[command] = arg
            else:
                chat["lastAsked"] = message
                MESSAGES[chat_id][1].append({"kind": "message", "role": "user", "text": message})
                WORKING_UNTIL[chat_id] = time.monotonic() + 8

            chat["lastActivityAt"] = int(time.time() * 1000)
            if operation == "answer":
                finish_item()

        if failure == "unknown_result":
            self.reply(502, {
                "error": "unknown_result",
                "message": "送れたか確認できませんでした。会話を更新して確かめてください。",
            })
        elif operation == "start":
            self.reply(201, {"chatId": chat_id})
        else:
            self.reply(200 if operation in ("command", "answer") else 202, {"ok": True})


def finish_item():
    ITEM["status"] = "done"
    next(bot for bot in BOTS if bot["name"] == BOT)["needsYou"] = 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--write-failure", choices=("409", "502", "503", "unknown_result"),
                        help="Inject this failure for POST requests (503 also applies to the commands list)")
    parser.add_argument("--write-delay", type=float, default=0, metavar="SECONDS",
                        help="Delay POST replies, including injected failures")
    options = parser.parse_args()
    if options.write_delay < 0:
        parser.error("--write-delay must be non-negative")
    with ThreadingHTTPServer(("127.0.0.1", options.port), MockHandler) as server:
        server.write_failure = options.write_failure
        server.write_delay = options.write_delay
        print(f"Mock API listening on 127.0.0.1:{server.server_port}", flush=True)
        server.serve_forever()
