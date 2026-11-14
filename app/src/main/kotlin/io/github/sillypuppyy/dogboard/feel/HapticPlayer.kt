package io.github.sillypuppyy.dogboard.feel

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Plays [HapticTap]s on the device vibrator.
 *
 * Android 16+ devices with envelope support get an intensity/sharpness envelope that mirrors the
 * tap description directly; older devices fall back to a scaled click primitive.
 */
class HapticPlayer(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private val supportsEnvelopes: Boolean =
        Build.VERSION.SDK_INT >= 36 && vibrator?.areEnvelopeEffectsSupported() == true

    private val minControlPointMs: Long = if (supportsEnvelopes && Build.VERSION.SDK_INT >= 36) {
        vibrator?.envelopeEffectInfo?.minControlPointDurationMillis ?: 0L
    } else 0L

    private val supportsClick: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) == true

    private var lastPressUptime: Long? = null

    init {
        android.util.Log.i(
            "DogBoardPerf",
            "haptics: envelopes=$supportsEnvelopes minControlPointMs=$minControlPointMs click=$supportsClick",
        )
    }

    val isAvailable: Boolean get() = vibrator?.hasVibrator() == true

    /** Plays the feedback for a key press of [feedbackClass]. Returns false if nothing was played. */
    fun play(feedbackClass: KeyFeedbackClass): Boolean {
        val now = SystemClock.uptimeMillis()
        val sinceLast = lastPressUptime?.let { now - it }
        lastPressUptime = now
        val tap = Haptics.tapFor(feedbackClass, sinceLast) ?: return false
        // haptics must never take the keyboard down: on any failure, fall back to the system haptic
        return try {
            val effect = effectFor(tap) ?: return false
            val v = vibrator ?: return false
            // tagged as touch feedback, so the system's touch-vibration setting and intensity apply
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            }
            true
        } catch (e: RuntimeException) {
            android.util.Log.w("DogBoardPerf", "haptic effect failed, falling back: $e")
            false
        }
    }

    private fun effectFor(tap: HapticTap): VibrationEffect? {
        // iOS-matched where the motor allows (Android 16 frequency control): a sine burst at the
        // iOS frequency for the tap's sharpness (80 * 2.875^S Hz, Haptics.frequencyHz), rising and
        // falling over iOS's 20 ms; strength is relative to this motor's maximum (Apple's absolute
        // output is unknown)
        if (supportsEnvelopes && Build.VERSION.SDK_INT >= 36) {
            val profile = runCatching { vibrator?.frequencyProfile }.getOrNull()
            if (profile != null && profile.maxFrequencyHz > profile.minFrequencyHz) {
                val f = Haptics.frequencyHz(tap.sharpness).toFloat().coerceIn(profile.minFrequencyHz, profile.maxFrequencyHz)
                val ramp = maxOf(IOS_RAMP_MS, minControlPointMs)
                return VibrationEffect.WaveformEnvelopeBuilder()
                    .setInitialFrequencyHz(f)
                    .addControlPoint(tap.intensity, f, ramp)
                    .addControlPoint(0f, f, ramp)
                    .build()
            }
        }
        if (supportsEnvelopes && Build.VERSION.SDK_INT >= 36) {
            val attack = maxOf(ATTACK_MS, minControlPointMs)
            val decay = maxOf(DECAY_MS, minControlPointMs)
            return VibrationEffect.BasicEnvelopeBuilder()
                .setInitialSharpness(tap.sharpness)
                .addControlPoint(tap.intensity, tap.sharpness, attack)
                .addControlPoint(0f, tap.sharpness, decay)
                .build()
        }
        if (supportsClick && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, tap.intensity)
                .compose()
        }
        return null
    }

    private companion object {
        /** iOS haptic engine: transient attack and decay, 20 ms each. */
        const val IOS_RAMP_MS = 20L
        const val ATTACK_MS = 4L
        const val DECAY_MS = 20L
    }
}
