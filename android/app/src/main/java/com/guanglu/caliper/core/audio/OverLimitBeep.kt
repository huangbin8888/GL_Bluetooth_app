package com.guanglu.caliper.core.audio

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * 超差蜂鸣：使用系统内置提示音，不需要音频文件与额外权限。
 *
 * @author AI-Generated
 */
internal class OverLimitBeep(private val onDiagnostic: (String) -> Unit) {

    private var generator: ToneGenerator? = null

    /** 播放一声蜂鸣；初始化或播放失败写诊断日志，不把异常抛回采集链路。 */
    fun play() {
        val tone = generator ?: createGenerator() ?: return
        val started = runCatching {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
        }.getOrElse { error ->
            onDiagnostic("BEEP_FAIL ${error.javaClass.simpleName}")
            return
        }
        if (!started) onDiagnostic("BEEP_FAIL startTone=false")
    }

    private fun createGenerator(): ToneGenerator? =
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME) }
            .getOrElse { error ->
                onDiagnostic("BEEP_FAIL ${error.javaClass.simpleName}")
                null
            }
            .also { generator = it }

    /** 释放音频资源。 */
    fun close() {
        generator?.release()
        generator = null
    }

    private companion object {
        const val BEEP_VOLUME = 80
        const val BEEP_DURATION_MS = 180
    }
}
