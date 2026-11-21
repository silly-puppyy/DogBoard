package io.github.sillypuppyy.dogboard.feel

import android.content.Context
import android.media.AudioAttributes
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * DogBoard's own key sounds (Settings › Sound and vibration › Key sound), generated here from a few numbers, no sound
 * files. Modelled on how iOS's key sounds are built (measured 2026-11-21, notes/research/key-sounds.md): the key
 * click is a tiny damped tone (iOS: 1.8 kHz, ~1 ms decay, 4.5 ms long), delete a lower smooth wave-packet (720 Hz),
 * a modifier two close tones. Our pitches and decays are our own.
 */
object KeySounds {
    enum class Kind { KEY, DELETE, MODIFIER, SPACE }

    /** A damped tone: frequency (Hz), decay (s), relative level. */
    private class Partial(val f: Double, val tau: Double, val level: Double = 1.0)

    /** A Gaussian wave-packet: frequency (Hz), centre and width (s). */
    private class Packet(val f: Double, val centre: Double, val width: Double)

    private class Voice(val length: Double, val partials: List<Partial> = emptyList(), val packet: Packet? = null, val noiseDecay: Double = 0.0)

    private class Style(val key: Voice, val delete: Voice, val modifier: Voice, val space: Voice)

    /** The styles by preference value; "system" is Android's own click and isn't here. */
    private val STYLES = mapOf(
        "tick" to Style(
            key = Voice(.006, listOf(Partial(2100.0, .0011))),
            delete = Voice(.010, packet = Packet(760.0, .004, .0011)),
            modifier = Voice(.006, listOf(Partial(1500.0, .0009), Partial(1100.0, .0012, .6))),
            space = Voice(.007, listOf(Partial(1700.0, .0013))),
        ),
        "drop" to Style(
            key = Voice(.008, listOf(Partial(1250.0, .0016))),
            delete = Voice(.012, packet = Packet(560.0, .005, .0014)),
            modifier = Voice(.008, listOf(Partial(950.0, .0012), Partial(1400.0, .0010, .5))),
            space = Voice(.009, listOf(Partial(1000.0, .0018))),
        ),
        "glass" to Style(
            key = Voice(.006, listOf(Partial(3300.0, .0013), Partial(5200.0, .0006, .25))),
            delete = Voice(.010, packet = Packet(1500.0, .004, .0010)),
            modifier = Voice(.006, listOf(Partial(2600.0, .0011), Partial(3500.0, .0009, .5))),
            space = Voice(.007, listOf(Partial(2700.0, .0015), Partial(4300.0, .0007, .25))),
        ),
        "thock" to Style(
            key = Voice(.010, listOf(Partial(700.0, .0020), Partial(1900.0, .0006, .35))),
            delete = Voice(.014, packet = Packet(380.0, .006, .0018)),
            modifier = Voice(.010, listOf(Partial(560.0, .0018), Partial(1500.0, .0007, .4))),
            space = Voice(.011, listOf(Partial(600.0, .0024), Partial(1600.0, .0007, .3))),
        ),
        "classic" to Style(
            key = Voice(.012, listOf(Partial(2100.0, .0015)), noiseDecay = .0025),
            delete = Voice(.014, packet = Packet(900.0, .004, .0012), noiseDecay = .003),
            modifier = Voice(.012, listOf(Partial(1500.0, .0012)), noiseDecay = .002),
            space = Voice(.013, listOf(Partial(1700.0, .0017)), noiseDecay = .003),
        ),
    )

    val styleIds: List<String> get() = STYLES.keys.toList()

    private const val SR = 44100
    // Low-latency static AudioTracks, built once per style: a press only rewinds a ready buffer (SoundPool has no
    // low-latency path and the sound came noticeably after the touch on a Pixel 10, user 2026-11-21: "a bit of
    // latency, disorienting"). Two tracks per voice, used in turn, so fast presses overlap instead of cutting off.
    // Typing sounds use the sonification stream (silent on silent, as Gboard's and an iPhone's); the settings'
    // previews and Play on silent use the media stream (a phone on vibrate previewed nothing).
    private const val COPIES = 2
    private var tracks: Array<Array<android.media.AudioTrack?>> = emptyArray()
    private var mediaTracks: Array<Array<android.media.AudioTrack?>> = emptyArray()
    private val next = IntArray(Kind.entries.size)
    private var loadedStyle: String? = null
    private val rng = java.util.Random(11)

    /** 16-bit mono PCM of [v], peak at 0.4 of full scale (iOS's clicks peak near -8 dBFS). */
    private fun render(v: Voice, seed: Long): ShortArray {
        val n = (v.length * SR).toInt()
        val noise = java.util.Random(seed)
        val x = DoubleArray(n) { i ->
            val t = i.toDouble() / SR
            var s = 0.0
            for (p in v.partials) s += p.level * sin(2 * PI * p.f * t) * exp(-t / p.tau) * minOf(1.0, t / 0.0002)
            v.packet?.let { q -> s += sin(2 * PI * q.f * (t - q.centre)) * exp(-0.5 * ((t - q.centre) / q.width).let { it * it }) }
            if (v.noiseDecay > 0) s += 0.5 * noise.nextGaussian() * exp(-t / v.noiseDecay)
            s
        }
        val mean = x.average()
        val peak = x.maxOf { kotlin.math.abs(it - mean) }.takeIf { it > 0 } ?: 1.0
        return ShortArray(n) { i -> ((x[i] - mean) / peak * 0.4 * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun track(pcm: ShortArray, usage: Int): android.media.AudioTrack =
        android.media.AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
            .setPerformanceMode(android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(pcm.size * 2)
            .build().also { it.write(pcm, 0, pcm.size) }

    /** Builds [style]'s sounds (once per style; a few ms: off the main thread when it can be). */
    @Synchronized
    fun prepare(@Suppress("UNUSED_PARAMETER") context: Context, style: String) {
        if (style == loadedStyle) return
        val st = STYLES[style] ?: return
        for (set in tracks + mediaTracks) for (t in set) t?.release()
        val pcm = Kind.entries.map { k ->
            render(when (k) { Kind.KEY -> st.key; Kind.DELETE -> st.delete; Kind.MODIFIER -> st.modifier; Kind.SPACE -> st.space }, k.ordinal.toLong())
        }
        tracks = Array(Kind.entries.size) { k -> Array(COPIES) { runCatching { track(pcm[k], AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) }.getOrNull() } }
        mediaTracks = Array(Kind.entries.size) { k -> Array(COPIES) { runCatching { track(pcm[k], AudioAttributes.USAGE_MEDIA) }.getOrNull() } }
        loadedStyle = style
    }

    /**
     * Plays [kind] in the loaded style at [volume] (0..1), varied by +-6 % per press so typing doesn't sound mechanical;
     * [preview]: on the media stream, heard on silent too (the settings' previews, and Play on silent).
     */
    fun play(kind: Kind, volume: Float, preview: Boolean = false) {
        val set = (if (preview) mediaTracks else tracks).getOrNull(kind.ordinal) ?: return
        val i = next[kind.ordinal]; next[kind.ordinal] = (i + 1) % COPIES
        val t = set[i] ?: return
        val v = (volume * (1f + (rng.nextFloat() - 0.5f) * 0.12f)).coerceIn(0f, 1f)
        runCatching {
            if (t.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.setVolume(v)
            t.play()
        }
    }
}
