package com.guanglu.caliper.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.guanglu.caliper.core.model.BEEP_PULSE_DURATION_MS
import com.guanglu.caliper.core.model.BEEP_VOLUME_PERCENT
import com.guanglu.caliper.core.model.beepPulseDelays
import com.guanglu.caliper.core.model.beepVibrationPattern

/**
 * 超差提醒：三连高音量蜂鸣叠加振动，不需要音频文件。
 *
 * @author AI-Generated
 */
internal class OverLimitBeep(context: Context, private val onDiagnostic: (String) -> Unit) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var generator: ToneGenerator? = null
    private val pendingPulses = mutableListOf<Runnable>()

    /** 播放一轮提醒；新一轮会先取消上一轮未执行的脉冲，避免叠加成噪音。 */
    fun play() {
        cancelPulses()
        val tone = generator ?: createGenerator() ?: return
        vibrate()
        beepPulseDelays().forEach { delay ->
            val pulse = Runnable {
                val started = runCatching {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_PULSE_DURATION_MS.toInt())
                }.getOrElse { error ->
                    onDiagnostic("BEEP_FAIL ${error.javaClass.simpleName}")
                    false
                }
                if (!started) onDiagnostic("BEEP_FAIL startTone=false")
            }
            pendingPulses.add(pulse)
            handler.postDelayed(pulse, delay)
        }
    }

    private fun createGenerator(): ToneGenerator? =
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME_PERCENT) }
            .getOrElse { error ->
                onDiagnostic("BEEP_FAIL ${error.javaClass.simpleName}")
                null
            }
            .also { generator = it }

    /** 振动波形与三声蜂鸣对齐；无振动器或失败时写诊断，不影响蜂鸣。 */
    private fun vibrate() {
        val vibrator = runCatching { resolveVibrator() }
            .getOrElse { error ->
                onDiagnostic("VIBRATE_INIT_FAIL ${error.javaClass.simpleName}")
                null
            }
        if (vibrator == null) {
            onDiagnostic("VIBRATE_UNAVAILABLE")
            return
        }
        runCatching { startVibration(vibrator) }
            .onFailure { error -> onDiagnostic("VIBRATE_FAIL ${error.javaClass.simpleName}") }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    @Suppress("DEPRECATION")
    private fun startVibration(vibrator: Vibrator) {
        val pattern = beepVibrationPattern()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun cancelPulses() {
        pendingPulses.forEach { handler.removeCallbacks(it) }
        pendingPulses.clear()
    }

    /** 释放音频资源并取消待执行的脉冲。 */
    fun close() {
        cancelPulses()
        generator?.release()
        generator = null
    }
}
