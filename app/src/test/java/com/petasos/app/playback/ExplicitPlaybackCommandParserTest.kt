package com.petasos.app.playback

import org.junit.Assert.*
import org.junit.Test
import java.text.Normalizer

class ExplicitPlaybackCommandParserTest {
    @Test fun recognizesOnlyExplicitWholeUtteranceControls() {
        mapOf(
            "재생 멈춰" to ExplicitPlaybackCommand.STOP,
            "계속 재생해 줘" to ExplicitPlaybackCommand.CONTINUE,
            "다음으로 넘어가" to ExplicitPlaybackCommand.NEXT,
            "처음부터 다시 들려줘" to ExplicitPlaybackCommand.REPLAY,
            "음악 틀어줘" to ExplicitPlaybackCommand.MUSIC,
        ).forEach { (text, command) -> assertEquals(command, ExplicitPlaybackCommandParser.parse(text)) }
    }

    @Test fun permitsOnlyHarmlessSpacingUnicodeCompositionAndSentenceEndNormalization() {
        assertEquals(ExplicitPlaybackCommand.STOP, ExplicitPlaybackCommandParser.parse("  재생\t멈춰!  "))
        assertEquals(ExplicitPlaybackCommand.MUSIC, ExplicitPlaybackCommandParser.parse("음악\u00a0틀어줘。"))
        assertEquals(ExplicitPlaybackCommand.REPLAY,
            ExplicitPlaybackCommandParser.parse(Normalizer.normalize("다시 들려줘", Normalizer.Form.NFD)))
    }

    @Test fun questionsNegationsQuotesConditionsAndCompoundUtterancesAreNotControls() {
        listOf(
            "재생 멈춰?", "재생을 멈추지 마", "음악 틀지 마", "다시 들려주지 마",
            "'멈춰'", "“멈춰”", "멈춰라는 말은 무슨 뜻이야", "멈춰서 보니 아름다웠어",
            "다음으로 넘어가도 될까", "내가 말하면 멈춰", "음악 틀어줘 그리고 다음으로 넘어가",
            "음악", "그만", "계속", "다음 이야기", "다음 곡", "가거도 이야기 다시 들려줘",
            "음악 https://example.invalid/song 틀어줘", "다시 들려줘, 재생 멈춰",
        ).forEach { assertNull(it, ExplicitPlaybackCommandParser.parse(it)) }
    }

    @Test fun rejectsEmptyOversizeMultilineAndInvisibleControlInputs() {
        listOf("", "   ", "재생\n멈춰", "재생\r멈춰", "재생\u2028멈춰", "재생\u200b멈춰",
            "\uFEFF멈춰", "멈춰\u0000", " ".repeat(161) + "멈춰")
            .forEach { assertNull(ExplicitPlaybackCommandParser.parse(it)) }
    }
}
