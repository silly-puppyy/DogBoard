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

    /**
     * A body that rings: an all-pole filter (coefficients a1..aN, fitted at [rate] Hz) struck once and kept ringing by
     * noise of level [noise] that falls by [decay] s to a held [floor], for the voice's whole length.
     */
    private class Resonator(val a: DoubleArray, val noise: Double, val decay: Double, val floor: Double, val rate: Int = 48000)

    private class Voice(val length: Double, val partials: List<Partial> = emptyList(), val packet: Packet? = null, val noiseDecay: Double = 0.0,
                        val res: Resonator? = null)

    private class Style(val key: Voice, val delete: Voice, val modifier: Voice, val space: Voice)

    // the resonances of the early iPhone's click (linear prediction, order 20, on the measured sound; ours to play,
    // not the recording: notes/research/key-sounds.md)
    private val TOCK = Voice(.0215, res = Resonator(doubleArrayOf(
        -4.309983, 9.029249, -11.641592, 9.810875, -5.478585, 2.723841, -2.680261, 2.916048, -1.486692, -0.448830,
        0.980930, -0.288028, -0.075367, -0.326875, 0.667253, -0.372071, -0.129311, 0.299744, -0.157226, 0.024547,
    ), noise = .2, decay = .001, floor = .05))

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
        // the early iPhone's key click (iPhone OS 1 to iOS 9, Gloss's era): our own shaped noise fitted to the measured
        // sound (notes/research/key-sounds.md: 21.5 ms, 1-4 kHz, centre ~3.1 kHz, a fast drop to a held tail); the
        // same click on every key, as it was then
        "tock" to TOCK.let { Style(it, it, it, it) },
        "classic" to Style(
            key = Voice(.012, listOf(Partial(2100.0, .0015)), noiseDecay = .0025),
            delete = Voice(.014, packet = Packet(900.0, .004, .0012), noiseDecay = .003),
            modifier = Voice(.012, listOf(Partial(1500.0, .0012)), noiseDecay = .002),
            space = Voice(.013, listOf(Partial(1700.0, .0017)), noiseDecay = .003),
        ),
    )

    /**
     * Recorded packs: assets/dogboard/sounds/<id>/<kind>-<n>.pcm, 16-bit mono at [PACK_RATE], keystrokes cut from CC0
     * Freesound recordings and levelled by loudness (user's picks 2026-11-21; sources in ATTRIBUTION.txt).
     */
    private val RECORDED = listOf("clack", "pen", "mouse", "mechkeys")
    private const val PACK_RATE = 48000

    val styleIds: List<String> get() = STYLES.keys.toList() + RECORDED

    /** [pack]'s takes for [kind] ("key", "space", "delete"), or empty. */
    private fun packTakes(context: Context, pack: String, kind: String): List<ShortArray> {
        val dir = "dogboard/sounds/$pack"
        val names = runCatching { context.assets.list(dir) }.getOrNull().orEmpty().filter { it.startsWith("$kind-") && it.endsWith(".pcm") }.sorted()
        return names.mapNotNull { n ->
            runCatching {
                val b = context.assets.open("$dir/$n").use { it.readBytes() }
                ShortArray(b.size / 2) { i -> ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort() }
            }.getOrNull()
        }
    }

    /** [pcm] played [ratio] times faster (linear interpolation): the output rate, and a variant's pitch. */
    private fun resample(pcm: ShortArray, ratio: Double): ShortArray {
        if (ratio == 1.0) return pcm
        val n = ((pcm.size - 1) / ratio).toInt().coerceAtLeast(1)
        return ShortArray(n) { i ->
            val x = i * ratio; val j = x.toInt(); val f = x - j
            val a = pcm[j.coerceAtMost(pcm.size - 1)].toDouble(); val b = pcm[(j + 1).coerceAtMost(pcm.size - 1)].toDouble()
            (a + (b - a) * f).toInt().toShort()
        }
    }

    // the output's own rate (usually 48 kHz): a track at another rate is resampled, and Android may refuse it the fast
    // (low-latency) path; 44.1 kHz was fixed before 2026-11-21
    private var SR = 48000
    // Low-latency static AudioTracks, built once per style: a press only rewinds a ready buffer (SoundPool has no
    // low-latency path and the sound came noticeably after the touch on a Pixel 10, "a bit of
    // latency, disorienting"). Two tracks per voice, used in turn, so fast presses overlap instead of cutting off.
    // Typing sounds use the sonification stream (silent on silent, as Gboard's and an iPhone's); the settings'
    // previews and Play on silent use the media stream (a phone on vibrate previewed nothing).
    // Four variants per voice, played in turn (round-robin, as game audio does with several recordings of one action):
    // each with its own pitch and decay, and each press its own volume. Volume alone (+-6 %) was barely audible and typing
    // sounded mechanical; recorded key sounds vary because every take differs (Kenney's UI Audio).
    // How much is Settings › Key sound › Variation (0-100): at 100 pitch +-7 %, decay +-24 %, volume +-20 %.
    private const val COPIES = 4
    private var tracks: Array<Array<android.media.AudioTrack?>> = emptyArray()
    private var mediaTracks: Array<Array<android.media.AudioTrack?>> = emptyArray()
    // the media-stream copies are made on their first use (previews, Play on silent): Android has few fast-path track slots
    // (8 per mixer, one its own) and typing needs only the sonification set
    private var loadedPcm: List<List<ShortArray>> = emptyList()
    private val next = IntArray(Kind.entries.size)
    private var loadedStyle: String? = null
    private var loadedVariation = -1
    private var variation = 0.5
    private val rng = java.util.Random(11)

    /** 16-bit mono PCM of [v], peak at 0.4 of full scale (iOS's clicks peak near -8 dBFS). */
    private fun render(v: Voice, seed: Long, variant: Int = 0, amount: Double = 0.5): ShortArray {
        val n = (v.length * SR).toInt()
        val noise = java.util.Random(seed * 31 + variant)
        // variant 0 is the voice as designed; the others shift pitch and decay a little (fixed seeds: the same set every time)
        val jitter = java.util.Random(seed * 131 + variant * 7919L)
        val pf = if (variant == 0) 1.0 else 1.0 + (jitter.nextDouble() * 2 - 1) * 0.07 * amount
        val df = if (variant == 0) 1.0 else 1.0 + (jitter.nextDouble() * 2 - 1) * 0.24 * amount
        val shaped = v.res?.let { ring(it, n, noise, df) }
        val x = DoubleArray(n) { i ->
            val t = i.toDouble() / SR
            var s = 0.0
            for (p in v.partials) s += p.level * sin(2 * PI * p.f * pf * t) * exp(-t / (p.tau * df)) * minOf(1.0, t / 0.0002)
            v.packet?.let { q -> s += sin(2 * PI * q.f * pf * (t - q.centre)) * exp(-0.5 * ((t - q.centre) / (q.width * df)).let { it * it }) }
            if (v.noiseDecay > 0) s += 0.5 * noise.nextGaussian() * exp(-t / v.noiseDecay)
            shaped?.let { s += it[i] }
            s
        }
        val mean = x.average()
        val peak = x.maxOf { kotlin.math.abs(it - mean) }.takeIf { it > 0 } ?: 1.0
        return ShortArray(n) { i -> ((x[i] - mean) / peak * 0.4 * Short.MAX_VALUE).toInt().toShort() }
    }

    /**
     * [r] struck and kept ringing for [n] samples, peak 1, a 0.8 ms fade at the end. At another output rate the
     * filter's own rate is kept (rendered at it, then resampled), so the pitch stays.
     */
    private fun ring(r: Resonator, n: Int, rng: java.util.Random, decayFactor: Double): DoubleArray {
        val m = (n.toDouble() * r.rate / SR).toInt().coerceAtLeast(2)
        val y = DoubleArray(m)
        for (i in 0 until m) {
            val t = i.toDouble() / r.rate
            var acc = (if (i == 0) 1.0 else 0.0) + r.noise * rng.nextGaussian() * (exp(-t / (r.decay * decayFactor)) + r.floor)
            for (k in r.a.indices) if (i - k - 1 >= 0) acc -= r.a[k] * y[i - k - 1]
            y[i] = acc
        }
        val peak = y.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
        val len = n.toDouble() / SR
        return DoubleArray(n) { j ->
            val pos = j.toDouble() * (m - 1) / (n - 1).coerceAtLeast(1)
            val i0 = pos.toInt().coerceAtMost(m - 2); val f = pos - i0
            val t = j.toDouble() / SR
            (y[i0] * (1 - f) + y[i0 + 1] * f) / peak * minOf(1.0, (len - t) / 0.0008)
        }
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

    /** Builds [style]'s sounds with [variationPercent] (0-100) of variation (again only when either changes; a few ms). */
    @Synchronized
    fun prepare(@Suppress("UNUSED_PARAMETER") context: Context, style: String, variationPercent: Int = 50) {
        if (style == loadedStyle && variationPercent == loadedVariation) return
        val st = STYLES[style]
        if (st == null && style !in RECORDED) return
        val amount = variationPercent.coerceIn(0, 100) / 100.0
        (context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)
            ?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in 8000..192000 }?.let { SR = it }
        for (set in tracks + mediaTracks) for (t in set) t?.release()
        val pcm = if (st != null) Kind.entries.map { k ->
            val voice = when (k) { Kind.KEY -> st.key; Kind.DELETE -> st.delete; Kind.MODIFIER -> st.modifier; Kind.SPACE -> st.space }
            List(COPIES) { variant -> render(voice, k.ordinal.toLong(), variant, amount) }
        } else {
            val key = packTakes(context, style, "key").ifEmpty { return }
            val takes = mapOf(
                Kind.KEY to key, Kind.MODIFIER to key,
                Kind.SPACE to packTakes(context, style, "space").ifEmpty { key },
                Kind.DELETE to packTakes(context, style, "delete").ifEmpty { key },
            )
            Kind.entries.map { k ->
                val list = takes.getValue(k)
                // the takes in turn; each variant a little higher or lower as Variation says (the first as recorded)
                val jitter = java.util.Random(k.ordinal * 131L)
                List(COPIES) { variant ->
                    val pf = if (variant == 0) 1.0 else 1.0 + (jitter.nextDouble() * 2 - 1) * 0.07 * amount
                    resample(list[variant % list.size], PACK_RATE.toDouble() / SR * pf)
                }
            }
        }
        tracks = Array(Kind.entries.size) { k -> Array(COPIES) { i -> runCatching { track(pcm[k][i], AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) }.getOrNull() } }
        mediaTracks = emptyArray()
        loadedPcm = pcm
        loadedStyle = style; loadedVariation = variationPercent; variation = amount
        // how many tracks Android actually put on its fast path (a mixer has 8 fast slots, one its own: 2026-11-21)
        fun fast(set: Array<Array<android.media.AudioTrack?>>) = set.sumOf { kind -> kind.count { it?.performanceMode == android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY } }
        android.util.Log.i("DogBoardPerf", "key sounds $style at $SR Hz: typing ${fast(tracks)} of ${tracks.sumOf { it.size }} tracks fast, media ${fast(mediaTracks)} of ${mediaTracks.sumOf { it.size }}")
        if (dev.patrickgold.florisboard.BuildConfig.DEBUG && !probed) { probed = true; appContext = context.applicationContext; probeFastPath() }
    }

    private var probed = false

    /**
     * Debug builds: which kinds of track this phone puts on the fast path (a Pixel 10 gave our static sonification tracks
     * none of it, 2026-11-21): static and streaming tracks for three usages, built, logged and released.
     */
    private fun probeFastPath() {
        val usages = listOf("sonification" to AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, "media" to AudioAttributes.USAGE_MEDIA, "game" to AudioAttributes.USAGE_GAME)
        val out = StringBuilder("fast path probe at $SR Hz:")
        for ((name, usage) in usages) for (static in listOf(true, false)) {
            val t = runCatching {
                val fmt = android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build()
                val min = android.media.AudioTrack.getMinBufferSize(SR, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                android.media.AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(fmt)
                    .setTransferMode(if (static) android.media.AudioTrack.MODE_STATIC else android.media.AudioTrack.MODE_STREAM)
                    .setPerformanceMode(android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(if (static) 4800 else min)
                    .build()
            }.getOrNull()
            val fast = t?.performanceMode == android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            out.append(" ").append(name).append(if (static) "/static=" else "/stream=").append(if (t == null) "fail" else if (fast) "FAST" else "normal")
            if (t != null && !static) out.append("(").append(t.bufferSizeInFrames).append(" frames)")
            t?.release()
        }
        // a streaming track asking for only two of the mixer's bursts, mono and stereo
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val burst = am?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        for (stereo in listOf(false, true)) {
            val mask = if (stereo) android.media.AudioFormat.CHANNEL_OUT_STEREO else android.media.AudioFormat.CHANNEL_OUT_MONO
            val t = runCatching {
                android.media.AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(mask).build())
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .setPerformanceMode(android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(maxOf(burst, 96) * 2 * (if (stereo) 4 else 2))
                    .build()
            }.getOrNull()
            out.append(if (stereo) " stream-small-stereo=" else " stream-small-mono=")
                .append(if (t == null) "fail" else if (t.performanceMode == android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) "FAST" else "normal")
            if (t != null) out.append("(").append(t.bufferSizeInFrames).append(" frames)")
            t?.release()
        }
        // the phone's low-latency support and where sound goes now (device types only)
        val pm = appContext?.packageManager
        out.append("; low_latency feature=").append(pm?.hasSystemFeature("android.hardware.audio.low_latency"))
            .append(" pro=").append(pm?.hasSystemFeature("android.hardware.audio.pro"))
            .append(" burst=").append(burst)
        val outs = am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).orEmpty().map { it.type }.distinct()
        out.append(" outputs=").append(outs.joinToString(",")) // 2 speaker, 7/8 Bluetooth SCO/A2DP, 26/27 BLE, 3/4 wired, 22 USB headset
        android.util.Log.i("DogBoardPerf", out.toString())
    }

    private var appContext: Context? = null

    /**
     * Plays [kind] in the loaded style at [volume] (0..1): the next of its variants, its volume varied per press;
     * [preview]: on the media stream, heard on silent too (the settings' previews, and Play on silent).
     */
    // playback runs on its own thread at audio priority: AudioTrack.stop/reload/play cost the UI thread a few ms per key
    // (the phone's stall samples showed AudioTrack.native_start, 2026-11-21) and waited behind its frames
    private val audio by lazy {
        val t = android.os.HandlerThread("DogBoardKeySounds", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
        android.os.Handler(t.looper)
    }

    /**
     * Plays 300 ms of silence on the typing stream ([media]: the media stream, for Play on silent), so an idle output (and
     * a suspended Bluetooth link) is awake by the first key; silence played still opens the route.
     */
    fun wakeOutput(media: Boolean = false) {
        audio.post {
            runCatching {
                val n = SR * 3 / 10
                val t = track(ShortArray(n), if (media) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                t.play()
                audio.postDelayed({ runCatching { t.stop(); t.release() } }, 400)
            }
        }
    }

    fun play(kind: Kind, volume: Float, preview: Boolean = false) {
        audio.post { playNow(kind, volume, preview) }
    }

    private fun playNow(kind: Kind, volume: Float, preview: Boolean) {
        if (preview && mediaTracks.isEmpty() && loadedPcm.isNotEmpty()) synchronized(this) {
            if (mediaTracks.isEmpty()) mediaTracks = Array(Kind.entries.size) { k -> Array(COPIES) { i -> runCatching { track(loadedPcm[k][i], AudioAttributes.USAGE_MEDIA) }.getOrNull() } }
        }
        val set = (if (preview) mediaTracks else tracks).getOrNull(kind.ordinal) ?: return Unit.also { logPlay("no ${if (preview) "media" else "typing"} tracks (${mediaTracks.size}/${tracks.size}, pcm ${loadedPcm.size})") }
        val i = next[kind.ordinal]; next[kind.ordinal] = (i + 1) % COPIES
        val t = set[i] ?: return Unit.also { logPlay("track $i of $kind missing") }
        val v = (volume * (1f + (rng.nextFloat() - 0.5f) * 0.4f * variation.toFloat())).coerceIn(0f, 1f)
        runCatching {
            if (t.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.setVolume(v)
            t.play()
            logPlay("played ${if (preview) "media" else "typing"} $kind at volume %.2f, state ${t.playState}, routed to ${t.routedDevice?.type}".format(v))
        }.onFailure { logPlay("play failed: $it") }
    }

    private var playLogs = 0

    /** Debug builds: the first few plays per process (2026-11-21: Play on silent on and nothing heard). */
    private fun logPlay(what: String) {
        if (dev.patrickgold.florisboard.BuildConfig.DEBUG && playLogs++ < 6) android.util.Log.i("DogBoardPerf", "key sound play: $what")
    }
}
