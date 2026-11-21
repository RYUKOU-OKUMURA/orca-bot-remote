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
        val text = "──── From: Bot (status) ────\r\n進捗です\r\n[Reply: command]\r\n────\r\n"

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
            ────
        """.trimIndent()

        assertEquals(
            MessagePresentation(MessageKind.MAIL, "メール（handoff）", "[Reply: 本文中の例]\n\n本文の最後", "引き継ぎ"),
            presentMessage("user", text),
        )
    }

    @Test
    fun mailWithoutTypeUsesPlainHeading() {
        val text = "──── From: Bot (dispatch:ctx_f2f0f2ccc93a) ────\n確認しました"

        assertEquals(MessagePresentation(MessageKind.MAIL, "メール", "確認しました"), presentMessage("user", text))
    }

    @Test
    fun ownerMetadataRemovesFirstLineAndFollowingBlankLine() {
        val text = "orcaTabId: structured-agent-session-123\r\n\r\n  こんにちは\r\n次の行"

        assertEquals(MessagePresentation(MessageKind.OWNER, "あなた", "  こんにちは\r\n次の行"), presentMessage("user", text))
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
