package com.petasos.app.playback

import java.text.Normalizer

/** Whole-utterance local controls only. Never run against model, source or summary text. */
object ExplicitPlaybackCommandParser {
    private val spaces = Regex("[\\p{Zs}\\t]+")
    private val sentenceEnd = Regex("[.!。！]+$")
    private val phrases = buildMap {
        fun register(command: ExplicitPlaybackCommand, vararg values: String) {
            values.forEach { check(put(it, command) == null) }
        }
        register(ExplicitPlaybackCommand.STOP,
            "멈춰", "멈춰 줘", "멈춰줘", "멈춰 주세요", "재생 정지", "재생 중지",
            "재생 멈춰", "재생을 멈춰", "재생을 멈춰 줘", "재생을 멈춰줘",
            "재생을 멈춰 주세요", "그만 재생해", "그만 재생해 줘")
        register(ExplicitPlaybackCommand.CONTINUE,
            "계속 재생해", "계속 재생해 줘", "계속 재생해줘", "계속 들려줘",
            "이어서 들려줘", "이어서 재생해", "이어서 재생해 줘", "이어서 재생해줘")
        register(ExplicitPlaybackCommand.NEXT,
            "건너뛰어", "건너뛰어 줘", "건너뛰어줘", "다음으로 넘어가", "다음으로 넘어가 줘",
            "다음으로 넘어가줘", "다음 콘텐츠", "지금 콘텐츠 건너뛰어", "지금 콘텐츠 건너뛰어 줘")
        register(ExplicitPlaybackCommand.REPLAY,
            "처음부터 다시 재생해", "처음부터 다시 들려줘", "처음부터 재생해",
            "다시 들려줘", "다시 재생해", "다시 재생해 줘", "다시 재생해줘")
        register(ExplicitPlaybackCommand.MUSIC,
            "음악 틀어줘", "음악 틀어 줘", "음악 틀어 주세요", "음악을 틀어줘",
            "음악을 틀어 줘", "음악 재생해", "음악을 재생해", "음악 재생해 줘")
    }

    fun parse(text: String): ExplicitPlaybackCommand? {
        // Bound normalization and reject multiline/hidden controls instead of joining separate requests.
        if (text.length !in 1..160 || text.any {
                (it.isISOControl() && it != '\t') || Character.getType(it) == Character.FORMAT.toInt() ||
                    it == '\u2028' || it == '\u2029'
            }) return null
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC).trim()
            .replace(spaces, " ").replace(sentenceEnd, "").trimEnd()
        return phrases[normalized]
    }
}
