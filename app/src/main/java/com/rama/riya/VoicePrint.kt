package com.rama.riya

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device voiceprint. Records your voice, turns it into MFCC features and stores a small
 * vector-quantisation "codebook" of how YOU sound. Nothing leaves the phone.
 */
object VoicePrint {
    const val RATE = 16000
    private const val FRAME = 400
    private const val HOP = 160
    private const val NFFT = 512
    private const val NMEL = 26
    private const val NCEP = 12
    private const val DIM = NCEP * 2
    private const val K = 32
    private const val PREF = "riya_voice"
    private const val KEY = "model"

    class Result(val accepted: Boolean, val enough: Boolean, val distortion: Double, val threshold: Double)
    private class Model(val mean: DoubleArray, val std: DoubleArray, val cb: Array<DoubleArray>, val thr: Double)

    fun isEnrolled(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).contains(KEY)
    fun clear(ctx: Context) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply() }

    // ---------- recording ----------

    @Suppress("MissingPermission")
    fun record(ctx: Context, ms: Int): ShortArray? {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val total = RATE * ms / 1000
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val out = ShortArray(total)
        try {
            rec.startRecording()
            var n = 0
            while (n < total) {
                val r = rec.read(out, n, min(1600, total - n))
                if (r <= 0) break
                n += r
            }
            return if (n >= total / 2) out.copyOf(n) else null
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            rec.release()
        }
    }

    // ---------- features (MFCC + delta) ----------

    private val window = DoubleArray(FRAME) { 0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1)) }
    private val melBank: Array<DoubleArray> by lazy { buildMel() }
    private val dct: Array<DoubleArray> by lazy {
        Array(NCEP) { i -> DoubleArray(NMEL) { j -> cos(PI * (i + 1) * (j + 0.5) / NMEL) } }
    }

    private fun hz2mel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
    private fun mel2hz(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1.0)

    private fun buildMel(): Array<DoubleArray> {
        val lo = hz2mel(80.0)
        val hi = hz2mel(7600.0)
        val pts = DoubleArray(NMEL + 2) { mel2hz(lo + (hi - lo) * it / (NMEL + 1)) }
        val bins = IntArray(NMEL + 2) { floor((NFFT + 1) * pts[it] / RATE).toInt() }
        return Array(NMEL) { m ->
            val f = DoubleArray(NFFT / 2 + 1)
            if (bins[m + 1] > bins[m]) for (k in bins[m] until bins[m + 1]) f[k] = (k - bins[m]).toDouble() / (bins[m + 1] - bins[m])
            if (bins[m + 2] > bins[m + 1]) for (k in bins[m + 1] until bins[m + 2]) f[k] = (bins[m + 2] - k).toDouble() / (bins[m + 2] - bins[m + 1])
            f
        }
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val t = re[i]; re[i] = re[j]; re[j] = t
                val u = im[i]; im[i] = im[j]; im[j] = u
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = i + k + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Feature vectors of the frames that contain speech, or null if there is too little speech. */
    private fun features(pcm: ShortArray): List<DoubleArray>? {
        val n = pcm.size
        if (n < FRAME * 10) return null
        val x = DoubleArray(n) { pcm[it] / 32768.0 }
        for (i in n - 1 downTo 1) x[i] -= 0.97 * x[i - 1]
        val nFrames = (n - FRAME) / HOP + 1
        val energy = DoubleArray(nFrames)
        val cep = ArrayList<DoubleArray>(nFrames)
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val logMel = DoubleArray(NMEL)
        for (t in 0 until nFrames) {
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            var e = 0.0
            for (i in 0 until FRAME) {
                val v = x[t * HOP + i]
                e += v * v
                re[i] = v * window[i]
            }
            energy[t] = e
            fft(re, im)
            for (m in 0 until NMEL) {
                val f = melBank[m]
                var s = 0.0
                for (k in 0..NFFT / 2) if (f[k] != 0.0) s += f[k] * (re[k] * re[k] + im[k] * im[k])
                logMel[m] = ln(s + 1e-10)
            }
            val c = DoubleArray(NCEP)
            for (i in 0 until NCEP) {
                var a = 0.0
                for (m in 0 until NMEL) a += dct[i][m] * logMel[m]
                c[i] = a
            }
            cep.add(c)
        }
        val sorted = energy.sortedArray()
        val p95 = sorted[min((nFrames * 0.95).toInt(), nFrames - 1)]
        val thr = max(p95 * 0.02, 0.003)
        val out = ArrayList<DoubleArray>()
        for (t in 1 until nFrames - 1) {
            if (energy[t] < thr) continue
            val v = DoubleArray(DIM)
            for (d in 0 until NCEP) {
                v[d] = cep[t][d]
                v[NCEP + d] = (cep[t + 1][d] - cep[t - 1][d]) / 2.0
            }
            out.add(v)
        }
        return if (out.size >= 40) out else null
    }

    // ---------- codebook (k-means) ----------

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
        return sqrt(s)
    }

    private fun nearest(cent: Array<DoubleArray>, f: DoubleArray): Int {
        var best = 0
        var bd = Double.MAX_VALUE
        for (c in cent.indices) {
            val d = dist(cent[c], f)
            if (d < bd) { bd = d; best = c }
        }
        return best
    }

    private fun kmeans(data: List<DoubleArray>, k: Int): Array<DoubleArray> {
        val kk = min(k, data.size)
        val cent = Array(kk) { data[it * data.size / kk].copyOf() }
        val assign = IntArray(data.size)
        repeat(12) {
            for (i in data.indices) assign[i] = nearest(cent, data[i])
            val sum = Array(kk) { DoubleArray(DIM) }
            val cnt = IntArray(kk)
            for (i in data.indices) {
                val a = assign[i]
                cnt[a]++
                for (d in 0 until DIM) sum[a][d] += data[i][d]
            }
            for (c in 0 until kk) if (cnt[c] > 0) for (d in 0 until DIM) cent[c][d] = sum[c][d] / cnt[c]
        }
        return cent
    }

    private fun distortion(cent: Array<DoubleArray>, frames: List<DoubleArray>): Double {
        var total = 0.0
        for (f in frames) total += dist(cent[nearest(cent, f)], f)
        return total / frames.size
    }

    // ---------- enrol / verify ----------

    /** Returns null on success, otherwise a message to show the user. */
    fun enroll(ctx: Context, clips: List<ShortArray>): String? {
        val per = clips.mapNotNull { features(it) }
        if (per.size < 4) return "I could not hear enough clear speech. Try again in a quiet room, closer to the mic."
        val flat = per.flatten()
        val mean = DoubleArray(DIM)
        val std = DoubleArray(DIM)
        for (f in flat) for (d in 0 until DIM) mean[d] += f[d]
        for (d in 0 until DIM) mean[d] /= flat.size
        for (f in flat) for (d in 0 until DIM) { val v = f[d] - mean[d]; std[d] += v * v }
        for (d in 0 until DIM) std[d] = sqrt(std[d] / flat.size) + 1e-6

        val norm = per.map { clip -> clip.map { f -> DoubleArray(DIM) { (f[it] - mean[it]) / std[it] } } }

        // Leave one clip out: how far is a sentence of yours from a model trained on the others?
        var worst = 0.0
        for (i in norm.indices) {
            val train = ArrayList<DoubleArray>()
            for (j in norm.indices) if (j != i) train.addAll(norm[j])
            worst = max(worst, distortion(kmeans(train, K), norm[i]))
        }
        val cb = kmeans(norm.flatten(), K)
        val thr = worst * 1.08

        val j = JSONObject()
        j.put("mean", JSONArray(mean.toList()))
        j.put("std", JSONArray(std.toList()))
        j.put("thr", thr)
        j.put("cb", JSONArray(cb.map { JSONArray(it.toList()) }))
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, j.toString()).apply()
        return null
    }

    private fun arr(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }

    private fun load(ctx: Context): Model? {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            val j = JSONObject(s)
            val cbj = j.getJSONArray("cb")
            Model(arr(j.getJSONArray("mean")), arr(j.getJSONArray("std")),
                Array(cbj.length()) { arr(cbj.getJSONArray(it)) }, j.getDouble("thr"))
        } catch (e: Exception) { null }
    }

    fun verify(ctx: Context, pcm: ShortArray): Result? {
        val m = load(ctx) ?: return null
        val f = features(pcm) ?: return Result(false, false, 99.0, m.thr)
        val norm = f.map { v -> DoubleArray(DIM) { (v[it] - m.mean[it]) / m.std[it] } }
        val d = distortion(m.cb, norm)
        return Result(d <= m.thr, true, d, m.thr)
    }
}
