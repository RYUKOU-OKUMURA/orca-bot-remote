package dev.okumura.orcaremote

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagePresentationTest {
    @Test
    fun workerQuestionShowsHeadingSubjectAndBody() {
        val text = """
            ──── From: DISPATCH:CTX_F2F0F2CCC93A (dispatch:ctx_f2f0f2ccc93a) (question) ────
            Subject: Question
            【Phase 0の結果と…】
            …本文…
            [Reply: orca orchestration reply --id msg_… --body "..."]
            ────────────────────────────────────────
        """.trimIndent()

        assertEquals(
            MessagePresentation(MessageKind.MAIL, "メール（question）", "【Phase 0の結果と…】\n…本文…", "Question"),
            presentMessage("user", text),
        )
    }

    @Test
    fun mailWithoutSubjectPreservesFirstBodyLine() {
        val text = "──── From: Bot (status) ────\r\n進捗です\r\n[Reply: orca orchestration reply --id msg_1]\r\n────\r\n"

        assertEquals(MessagePresentation(MessageKind.MAIL, "メール（status）", "進捗です"), presentMessage("user", text))
    }

    @Test
    fun mailWithoutReplyPreservesBodyIncludingReplyLookalike() {
        val text = """
            ──── From: Bot (handoff) ────
            Subject: 引き継ぎ
            [Reply: 本文中の例]
            ────
            本文の最後
            [Reply: 例]
            ────
        """.trimIndent()

        assertEquals(
            MessagePresentation(
                MessageKind.MAIL,
                "メール（handoff）",
                "[Reply: 本文中の例]\n────\n本文の最後\n[Reply: 例]\n────",
                "引き継ぎ",
            ),
            presentMessage("user", text),
        )
    }

    @Test
    fun ownerFromLineWithoutMailTypeStaysUnchanged() {
        val text = "──── From: Bot (dispatch:ctx_f2f0f2ccc93a) ────\n確認しました"

        assertEquals(MessagePresentation(MessageKind.OWNER, "あなた", text), presentMessage("user", text))
    }

    @Test
    fun ownerFromLineWithoutClosingRuleStaysUnchanged() {
        val text = "──── From: Bot (status)\r\nSubject: メモ\r\n確認しました"

        assertEquals(MessagePresentation(MessageKind.OWNER, "あなた", text), presentMessage("user", text))
    }

    @Test
    fun mailWithCrSeparatorsRemovesOnlyTrailingReplyHint() {
        val text = "──── From: Bot (status) ────\rSubject: 件名\r" +
            "[Reply: orca orchestration reply --id sample]\r────\r本文\r" +
            "[Reply: orca orchestration reply --id msg_1]"

        assertEquals(
            MessagePresentation(
                MessageKind.MAIL,
                "メール（status）",
                "[Reply: orca orchestration reply --id sample]\n────\n本文",
                "件名",
            ),
            presentMessage("user", text),
        )
    }

    @Test
    fun ownerMetadataRemovesFirstLineAndFollowingBlankLine() {
        for (lineBreak in listOf("\n", "\r\n", "\r")) {
            val body = "  こんにちは${lineBreak}次の行"
            val text = "orcaTabId: structured-agent-session-123${lineBreak}$lineBreak$body"
            assertEquals(MessagePresentation(MessageKind.OWNER, "あなた", body), presentMessage("user", text))
        }
    }

    @Test
    fun ownerMetadataLookalikesStayUnchanged() {
        val setting = "orcaTabId: 設定の説明\n\n本文"
        val withoutBlankLine = "orcaTabId: structured-agent-session-123\r\n本文"

        for (text in listOf(setting, withoutBlankLine)) {
            assertEquals(MessagePresentation(MessageKind.OWNER, "あなた", text), presentMessage("user", text))
        }
    }

    @Test
    fun ordinaryOwnerMessageKeepsMetadataLikeTextBelowFirstLine() {
        val text = "こんにちは\n\norcaTabId: example"

        assertEquals(MessagePresentation(MessageKind.OWNER, "あなた", text), presentMessage("user", text))
    }

    @Test
    fun assistantMessageKeepsMailLikeText() {
        val text = "──── From: Bot (status) ────\nSubject: 例\n確認しました"

        assertEquals(MessagePresentation(MessageKind.OTHER, "Bot", text), presentMessage("assistant", text))
    }
}
