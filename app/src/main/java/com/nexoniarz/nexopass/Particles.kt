package com.nexoniarz.nexopass

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.sqrt
import kotlin.random.Random

private const val COUNT = 46
private const val LINK_DP = 110f

/**
 * Drifting dots joined by faint lines when they come close, over two soft
 * glows. Stands still when turned off in settings or when the system
 * turned animations off.
 */
@Composable
fun ParticleBackground(dark: Boolean, animated: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val dot = if (dark) Color.White else MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val systemAnimations = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val animate = animated && systemAnimations
    val link = with(LocalDensity.current) { LINK_DP.dp2px(density) }

    // Positions and speeds as fractions of the screen, so rotation keeps the layout.
    val p = remember {
        val r = Random(7)
        Array(COUNT) { floatArrayOf(r.nextFloat(), r.nextFloat(), (r.nextFloat() - .5f) * .00012f, (r.nextFloat() - .5f) * .00012f, .6f + r.nextFloat() * 1.6f) }
    }
    var frame by remember { mutableLongStateOf(0L) }
    if (animate) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameMillis { now ->
                    val dt = if (last == 0L) 16f else (now - last).coerceAtMost(48).toFloat()
                    last = now
                    for (q in p) {
                        q[0] = (q[0] + q[2] * dt + 1f) % 1f
                        q[1] = (q[1] + q[3] * dt + 1f) % 1f
                    }
                    frame = now
                }
            }
        }
    }

    Box(modifier.fillMaxSize().background(bg)) {
        Canvas(Modifier.fillMaxSize()) {
            frame // read so every frame redraws
            val w = size.width
            val h = size.height
            drawRect(Brush.radialGradient(listOf(Violet.copy(alpha = if (dark) .28f else .16f), Color.Transparent), Offset(w * .1f, h * .12f), w * .9f))
            drawRect(Brush.radialGradient(listOf(Cyan.copy(alpha = if (dark) .18f else .12f), Color.Transparent), Offset(w * .95f, h * .85f), w * .9f))
            for (i in 0 until COUNT) {
                val a = p[i]
                val ax = a[0] * w
                val ay = a[1] * h
                for (j in i + 1 until COUNT) {
                    val dx = ax - p[j][0] * w
                    val dy = ay - p[j][1] * h
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < link) {
                        drawLine(dot.copy(alpha = (1f - d / link) * .22f), Offset(ax, ay), Offset(p[j][0] * w, p[j][1] * h), strokeWidth = 1.2f)
                    }
                }
                drawCircle(dot.copy(alpha = .55f), radius = a[4] * density, center = Offset(ax, ay))
            }
        }
        content()
    }
}

private fun Float.dp2px(density: Float) = this * density
