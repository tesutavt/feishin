package com.tesuta.tesuplayer.ui

import android.media.audiofx.Visualizer
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

private const val BANDS = 32

/** Spectrum bars of what plays now, like the desktop visualizer (needs the microphone permission) */
@Composable
fun VisualizerBars(sessionId: Int, playing: Boolean, modifier: Modifier = Modifier) {
    val levels = remember { mutableStateOf(FloatArray(BANDS)) }
    DisposableEffect(sessionId, playing) {
        val visualizer = if (sessionId != 0 && playing) runCatching { Visualizer(sessionId) }.getOrNull() else null
        runCatching {
            visualizer?.apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, rate: Int) = Unit
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, rate: Int) {
                        levels.value = bands(fft, levels.value)
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        }
        onDispose {
            runCatching { visualizer?.enabled = false }
            visualizer?.release()
            levels.value = FloatArray(BANDS)
        }
    }
    val brush = Brush.verticalGradient(listOf(Palette.blue, Palette.lavender, Palette.mauve))
    Canvas(modifier) {
        val values = levels.value
        val gap = size.width / BANDS * 0.3f
        val width = size.width / BANDS - gap
        values.forEachIndexed { i, level ->
            val h = (size.height * level).coerceAtLeast(2f)
            drawRoundRect(
                brush, Offset(i * (width + gap) + gap / 2, size.height - h), Size(width, h),
                CornerRadius(width / 2, width / 2), alpha = 0.85f,
            )
        }
    }
}

/** FFT (re/im byte pairs) -> log-spaced bands in 0..1, falling smoothly */
private fun bands(fft: ByteArray, previous: FloatArray): FloatArray {
    val bins = fft.size / 2
    return FloatArray(BANDS) { b ->
        val from = bins.toDouble().pow(b.toDouble() / BANDS).toInt().coerceAtLeast(1)
        val to = bins.toDouble().pow((b + 1).toDouble() / BANDS).toInt().coerceIn(from + 1, bins)
        var peak = 0.0
        for (k in from until to) peak = maxOf(peak, hypot(fft[2 * k].toDouble(), fft[2 * k + 1].toDouble()))
        val level = (20 * log10(peak + 1) / 42).toFloat().coerceIn(0f, 1f)
        maxOf(level, previous[b] * 0.85f)
    }
}
