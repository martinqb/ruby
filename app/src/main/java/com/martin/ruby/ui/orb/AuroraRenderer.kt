package com.martin.ruby.ui.orb

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.exp
import kotlin.math.tan
import kotlin.random.Random

/**
 * Draws the Aurora Orb with plain Android graphics, layer by layer as in the reference:
 * a shadow, a glowing halo, a night sky with twinkling stars, three aurora "veils" (stripes of
 * color drifting sideways while the veil sways), and a darkening "film" at the edge.
 *
 * Kept light: everything slow to make is made once. The veils' blur (20, 14 and 8 px in the
 * reference) is worked into small stripe images up front, so each frame only moves and fades
 * them; nothing is blurred while animating. A frame is about 35 simple draws.
 *
 * Sizes in the reference are pixels on a 168 px orb; here they're scaled to [orbPx].
 */
class AuroraRenderer(private val orbPx: Float, theme: OrbTheme = OrbTheme.AURORA) {

    private val k = orbPx / 168f
    private val radius = orbPx / 2f

    private val normal = theme.normal()
    private val thinking = theme.thinking()
    private val error = AuroraPalette.error()
    private val palettes = arrayOf(normal, thinking, error)

    // Veils

    private class VeilSpec(
        val periodPx: Float, // in the reference's px
        val stops: FloatArray, // px within the period
        val colors: (AuroraPalette) -> IntArray, // 0 = transparent
        val blurPx: Float,
        val maskStops: FloatArray, // fraction of the veil's height
        val maskAlphas: FloatArray,
        val swaySeconds: Float,
        val swayDelay: Float,
        val sway: Array<FloatArray>, // tx%, ty%, skewX°, rotate°, scaleY at SWAY_STOPS
        val driftSeconds: Float,
        val driftPx: Float, // how far the stripes move per loop (negative: left)
    )

    private val veils = arrayOf(
        VeilSpec(
            210f, floatArrayOf(0f, 64f, 118f, 162f, 210f), { p -> intArrayOf(p.green, 0, p.cyan, 0, p.green) }, 20f,
            floatArrayOf(0f, 0.26f, 0.82f, 1f), floatArrayOf(1f, 1f, 0f, 0f),
            17.9f, 7.3f,
            arrayOf(
                floatArrayOf(-1.6f, 1.2f, 2.6f, -2.4f, -1.6f), floatArrayOf(-0.4f, 0.7f, -0.8f, 0.9f, -0.4f),
                floatArrayOf(-6f, -7.5f, -5f, -8f, -6f), floatArrayOf(-2f, 1.2f, 2.6f, -1.4f, -2f),
                floatArrayOf(1.03f, 0.98f, 1.07f, 0.96f, 1.03f),
            ),
            37f, -210f,
        ),
        VeilSpec(
            130f, floatArrayOf(0f, 16f, 24f, 31f, 52f, 70f, 86f, 101f, 114f, 130f),
            { p -> intArrayOf(0, p.green, p.hi, p.green, 0, 0, p.pink, 0, p.cyan, 0) }, 14f,
            floatArrayOf(0f, 0.18f, 0.76f, 1f), floatArrayOf(1f, 1f, 0f, 0f),
            13.3f, 4.1f,
            arrayOf(
                floatArrayOf(1.8f, -1.4f, -2.8f, 2.2f, 1.8f), floatArrayOf(0.5f, -0.7f, 0.6f, -0.5f, 0.5f),
                floatArrayOf(-8f, -9.5f, -7f, -10f, -8f), floatArrayOf(2.2f, -1.6f, -3f, 1.1f, 2.2f),
                floatArrayOf(1f, 1.06f, 0.95f, 1.04f, 1f),
            ),
            19.1f, 130f,
        ),
        VeilSpec(
            84f, floatArrayOf(0f, 13f, 19f, 25f, 34f, 46f, 52f, 59f, 67f, 72f, 84f),
            { p -> intArrayOf(0, 0, p.hi, p.green, 0, 0, p.cyan, 0, 0, p.pink, 0) }, 8f,
            floatArrayOf(0f, 0.10f, 0.34f, 0.66f, 1f), floatArrayOf(1f, 1f, 1f, 0f, 0f),
            9.7f, 2.9f,
            arrayOf(
                floatArrayOf(-0.8f, 1.4f, 2f, -1.8f, -0.8f), floatArrayOf(0.3f, -0.4f, 0.5f, -0.3f, 0.3f),
                floatArrayOf(-9f, -8f, -10f, -8.5f, -9f), floatArrayOf(-1.2f, 0.9f, 1.6f, -0.8f, -1.2f),
                floatArrayOf(1.02f, 1.08f, 0.97f, 1.05f, 1.02f),
            ),
            11.3f, -84f,
        ),
    )

    /** [veil][palette] stripe images, each with its blur and fade worked in, as repeating shaders. */
    private val veilShaders: Array<Array<BitmapShader>> = Array(veils.size) { v ->
        Array(palettes.size) { p -> BitmapShader(veilTexture(veils[v], palettes[p]), Shader.TileMode.REPEAT, Shader.TileMode.CLAMP) }
    }

    private val veilWidth = orbPx * 1.48f // inset -16% -24% in the reference
    private val veilHeight = orbPx * 1.32f
    private val veilPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true // smooths the small images as they're enlarged: part of the blur
        blendMode = BlendMode.PLUS // the reference's plus-lighter: overlapping light adds up
    }
    private val matrix = Matrix()

    // Sky, stars, halo, film, shadow

    private val skyShaders = arrayOf(normal, error).map { p -> skyShaders(p) }
    private val skyPaint = Paint().apply { isAntiAlias = true }

    /** x, y (from the orb's top left, px), radius, brightness: the twinkling layer, then the faint fixed one. */
    private val twinkleStars = stars(
        floatArrayOf(13f, 21f, 1.3f, 0.95f, 83f, 61f, 49f, 0.9f, 0.85f, 97f, 37f, 83f, 1.6f, 0.92f, 118f,
            92f, 11f, 1f, 0.75f, 139f, 108f, 68f, 1.2f, 0.85f, 151f),
    )
    private val fixedStars = stars(
        floatArrayOf(21f, 84f, 1f, 0.5f, 127f, 111f, 31f, 0.8f, 0.42f, 149f, 67f, 119f, 1.2f, 0.36f, 173f),
    )
    private val starPaint = Paint().apply { isAntiAlias = true; color = Color.rgb(226, 238, 255) }

    private val haloShaders = palettes.map { p ->
        RadialGradient(0f, 0f, orbPx * 0.74f, intArrayOf(0, p.glow, 0), floatArrayOf(0.58f, 0.71f, 0.92f), Shader.TileMode.CLAMP)
    }
    private val haloPaint = Paint().apply { isAntiAlias = true }

    private val filmPaints = filmPaints()
    private val edgePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, k)
        color = Color.argb(18, 154, 196, 235)
    }

    private val shadowPaint = Paint().apply {
        isAntiAlias = true
        shader = RadialGradient(
            0f, 22f * k, radius + 24f * k,
            // Roughly a blur's bell-shaped fall-off, not a straight fade, which looked heavy.
            intArrayOf(
                Color.argb(128, 8, 12, 26), Color.argb(128, 8, 12, 26), Color.argb(64, 8, 12, 26),
                Color.argb(14, 8, 12, 26), 0,
            ),
            floatArrayOf(
                0f, (radius - 42f * k) / (radius + 24f * k), (radius - 20f * k) / (radius + 24f * k),
                radius / (radius + 24f * k), 1f,
            ),
            Shader.TileMode.CLAMP,
        )
    }

    /** Draws the orb centered on ([cx], [cy]). */
    fun draw(canvas: Canvas, cx: Float, cy: Float, anim: OrbAnimator) {
        val mix = anim.mix
        val t = anim.time
        val level = anim.level
        val bass = anim.bass
        val treble = anim.treble
        val listen = mix.weight(OrbState.LISTENING)
        val speak = mix.weight(OrbState.SPEAKING)
        val think = mix.weight(OrbState.THINKING)
        val connect = mix.weight(OrbState.CONNECTING)
        val err = mix.weight(OrbState.ERROR)
        // The error state's quick double pulse: the orb, its halo and its front light all beat.
        val beat = if (err > 0.002f) err * OrbMath.errorPulse(t) else 0f
        val paletteWeights = floatArrayOf((1f - think - err).coerceAtLeast(0f), think, err)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawCircle(0f, 22f * k, radius + 24f * k, shadowPaint)

        // Listening makes the whole orb swell with the voice.
        val orbScale = 1f + (0.012f + 0.05f * listen) * bass + 0.035f * beat
        canvas.scale(orbScale, orbScale)

        // Halo
        val haloOpacity = 0.16f + (0.1f + 0.3f * listen) * level + (0.06f + 0.22f * listen) * bass + 0.45f * beat
        val haloScale = 1f + (0.01f + 0.03f * listen) * level + (0.02f + 0.07f * listen) * bass + 0.06f * beat
        canvas.save()
        canvas.scale(haloScale, haloScale)
        for (p in palettes.indices) {
            val w = paletteWeights[p]
            if (w < 0.002f) continue
            haloPaint.shader = haloShaders[p]
            haloPaint.alpha = alpha(haloOpacity * w)
            canvas.drawCircle(0f, 0f, orbPx * 0.74f, haloPaint)
        }
        canvas.restore()

        // Sky: normal, with the error sky faded in over it.
        drawSky(canvas, 0, 1f)
        if (err > 0.002f) drawSky(canvas, 1, err)

        // Stars
        val twinkle = OrbMath.keyframes((t / 7.3).toFloat(), SWAY_STOPS, TWINKLE)
        drawStars(canvas, fixedStars, 1f)
        drawStars(canvas, twinkleStars, twinkle)

        // Veils
        val pulse = OrbMath.keyframes((t / 4.6).toFloat(), PULSE_STOPS, PULSE)
        val pulseSlow = OrbMath.keyframes((t / 4.6).toFloat(), PULSE_SLOW_STOPS, PULSE_SLOW)
        val opacities = floatArrayOf(
            0.4f + 0.16f * level + 0.16f * bass,
            0.5f + (0.08f + 0.18f * speak) * level + (0.06f + 0.26f * speak) * treble,
            (0.08f + (0.2f + 0.4f * speak) * level + (0.16f + 0.44f * speak) * treble) * (1f - connect - think).coerceAtLeast(0f) +
                (0.06f + 0.44f * pulseSlow) * connect + (0.14f + 0.5f * pulse) * think + 0.5f * beat,
        )
        for (v in veils.indices) {
            val spec = veils[v]
            veilMatrix(spec, t)
            for (p in palettes.indices) {
                val w = paletteWeights[p]
                if (w < 0.002f) continue
                val shader = veilShaders[v][p]
                shader.setLocalMatrix(matrix)
                veilPaint.shader = shader
                veilPaint.alpha = alpha(opacities[v] * w)
                canvas.drawCircle(0f, 0f, radius, veilPaint)
            }
        }

        // Film
        for (paint in filmPaints) canvas.drawCircle(0f, 0f, radius, paint)
        canvas.drawCircle(0f, 0f, radius - edgePaint.strokeWidth / 2f, edgePaint)
        canvas.restore()
    }

    private fun drawSky(canvas: Canvas, which: Int, opacity: Float) {
        for (shader in skyShaders[which]) {
            skyPaint.shader = shader
            skyPaint.alpha = alpha(opacity)
            canvas.drawCircle(0f, 0f, radius, skyPaint)
        }
    }

    private fun drawStars(canvas: Canvas, stars: FloatArray, opacity: Float) {
        var i = 0
        while (i < stars.size) {
            starPaint.alpha = alpha(stars[i + 3] * opacity)
            canvas.drawCircle(stars[i], stars[i + 1], stars[i + 2], starPaint)
            i += 4
        }
    }

    /** Where the veil's stripes are now: drifted sideways, inside a swaying, slanted veil. */
    private fun veilMatrix(spec: VeilSpec, t: Double) {
        val swayAt = ((t + spec.swayDelay) / spec.swaySeconds).toFloat()
        val tx = OrbMath.keyframes(swayAt, SWAY_STOPS, spec.sway[0]) / 100f * veilWidth
        val ty = OrbMath.keyframes(swayAt, SWAY_STOPS, spec.sway[1]) / 100f * veilHeight
        val skew = OrbMath.keyframes(swayAt, SWAY_STOPS, spec.sway[2])
        val rotate = OrbMath.keyframes(swayAt, SWAY_STOPS, spec.sway[3])
        val scaleY = OrbMath.keyframes(swayAt, SWAY_STOPS, spec.sway[4])
        val loop = t / spec.driftSeconds
        val drift = ((loop - kotlin.math.floor(loop)) * spec.driftPx * k).toFloat()
        val originX = veilWidth * 0.5f
        val originY = veilHeight * 0.12f

        matrix.setScale(spec.periodPx * k / TEXTURE_WIDTH, veilHeight / TEXTURE_HEIGHT)
        matrix.postTranslate(drift, 0f)
        matrix.postTranslate(-originX, -originY)
        matrix.postScale(1f, scaleY)
        matrix.postRotate(rotate)
        matrix.postSkew(tan(Math.toRadians(skew.toDouble())).toFloat(), 0f)
        matrix.postTranslate(tx + originX, ty + originY)
        // The veil box sits 24% left of and 16% above the orb; the canvas origin is the orb's center.
        matrix.postTranslate(-radius - 0.24f * orbPx, -radius - 0.16f * orbPx)
    }

    // Made once

    /**
     * One veil's stripes for one palette as a small image: a single repeat of the stripes,
     * blurred sideways (they're vertical, so that's the whole blur), faded toward the bottom.
     */
    private fun veilTexture(spec: VeilSpec, palette: AuroraPalette): Bitmap {
        val colors = spec.colors(palette)
        // Premultiplied red, green, blue, alpha across one period, at 4 samples per reference px.
        val fine = (spec.periodPx * 4).toInt()
        val row = Array(fine) { i -> gradientAt(i / 4f, spec.stops, colors) }
        val blurred = gaussianWrap(row, spec.blurPx * 4)
        val out = IntArray(TEXTURE_WIDTH * TEXTURE_HEIGHT)
        for (x in 0 until TEXTURE_WIDTH) {
            // Average the fine samples under this texel.
            val from = x * fine / TEXTURE_WIDTH
            val to = maxOf(from + 1, (x + 1) * fine / TEXTURE_WIDTH)
            val c = FloatArray(4)
            for (i in from until to) for (j in 0..3) c[j] += blurred[i][j]
            for (j in 0..3) c[j] /= (to - from)
            for (y in 0 until TEXTURE_HEIGHT) {
                val mask = linear((y + 0.5f) / TEXTURE_HEIGHT, spec.maskStops, spec.maskAlphas)
                out[y * TEXTURE_WIDTH + x] = unpremultiplied(c, mask)
            }
        }
        return Bitmap.createBitmap(out, TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    /** The reference's sky: a vertical gradient with a lighter glow at the top. */
    private fun skyShaders(p: AuroraPalette): List<Shader> {
        val base = LinearGradient(0f, -radius, 0f, radius, intArrayOf(p.skyTop, p.skyMid, p.skyLow), floatArrayOf(0f, 0.56f, 1f), Shader.TileMode.CLAMP)
        // radial-gradient(125% 90% at 50% -12%, hi 0%, top 46%, transparent 74%): an ellipse.
        val glow = RadialGradient(0f, 0f, 1f, intArrayOf(p.skyHi, p.skyTop, p.skyTop and 0x00FFFFFF), floatArrayOf(0f, 0.46f, 0.74f), Shader.TileMode.CLAMP)
        glow.setLocalMatrix(Matrix().apply {
            setScale(orbPx * 1.25f, orbPx * 0.9f) // the ellipse's two radii
            postTranslate(0f, -radius - 0.12f * orbPx)
        })
        return listOf(base, glow)
    }

    private fun filmPaints(): List<Paint> {
        val bottom = LinearGradient(
            0f, radius, 0f, -radius,
            intArrayOf(Color.argb(204, 3, 6, 13), Color.argb(77, 4, 8, 16), 0),
            floatArrayOf(0f, 0.10f, 0.30f), Shader.TileMode.CLAMP,
        )
        val edge = RadialGradient(
            0f, 0f, radius,
            intArrayOf(0, Color.argb(107, 3, 7, 15), Color.argb(179, 2, 5, 12)),
            floatArrayOf(0.70f, 0.94f, 1f), Shader.TileMode.CLAMP,
        )
        val grain = BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        return listOf(bottom, edge, grain).map { s -> Paint().apply { isAntiAlias = true; shader = s } }
    }

    /** A faint film grain, as the reference's noise texture. */
    private fun grain(): Bitmap {
        val size = 128
        val random = Random(7)
        val pixels = IntArray(size * size) {
            val v = random.nextInt(256)
            Color.argb((random.nextFloat() * 0.05f * 255).toInt(), v, v, v)
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** The reference's star layers: dots repeated on tiles; only those inside the orb. */
    private fun stars(layers: FloatArray): FloatArray {
        val out = ArrayList<Float>()
        var i = 0
        while (i < layers.size) {
            val x0 = layers[i]
            val y0 = layers[i + 1]
            val r = layers[i + 2]
            val a = layers[i + 3]
            val tile = layers[i + 4]
            var x = x0
            while (x < 168f) {
                var y = y0
                while (y < 168f) {
                    val dx = x - 84f
                    val dy = y - 84f
                    if (dx * dx + dy * dy < 80f * 80f) {
                        out += dx * k; out += dy * k; out += maxOf(0.6f, r * 0.6f * k); out += a
                    }
                    y += tile
                }
                x += tile
            }
            i += 5
        }
        return out.toFloatArray()
    }

    private companion object {
        const val TEXTURE_WIDTH = 64
        const val TEXTURE_HEIGHT = 32

        val SWAY_STOPS = floatArrayOf(0f, 0.23f, 0.47f, 0.71f, 1f)
        val TWINKLE = floatArrayOf(0.95f, 0.62f, 0.88f, 0.55f, 0.95f)
        val PULSE_STOPS = floatArrayOf(0f, 0.275f, 0.5f, 0.775f, 1f)
        val PULSE = floatArrayOf(0f, 1f, 0f, 1f, 0f)
        val PULSE_SLOW_STOPS = floatArrayOf(0f, 0.55f, 1f)
        val PULSE_SLOW = floatArrayOf(0f, 1f, 0f)

        fun alpha(opacity: Float) = (opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

        /** A straight (not eased) gradient value at [x] between [stops]. */
        fun linear(x: Float, stops: FloatArray, values: FloatArray): Float {
            if (x <= stops.first()) return values.first()
            for (i in 0 until stops.size - 1) {
                if (x <= stops[i + 1]) {
                    val span = stops[i + 1] - stops[i]
                    return if (span <= 0f) values[i + 1] else values[i] + (values[i + 1] - values[i]) * (x - stops[i]) / span
                }
            }
            return values.last()
        }

        /** Premultiplied RGBA (0..1) of a CSS repeating gradient at [x]; 0 in [colors] is transparent. */
        fun gradientAt(x: Float, stops: FloatArray, colors: IntArray): FloatArray {
            fun rgba(c: Int) = if (c == 0) FloatArray(4) else floatArrayOf(
                Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, 1f,
            )
            var i = 0
            while (i < stops.size - 2 && x > stops[i + 1]) i++
            val u = ((x - stops[i]) / (stops[i + 1] - stops[i])).coerceIn(0f, 1f)
            val a = rgba(colors[i])
            val b = rgba(colors[i + 1])
            return FloatArray(4) { a[it] + (b[it] - a[it]) * u }
        }

        /** Gaussian blur with standard deviation [sigma] samples, wrapping around (the stripes repeat). */
        fun gaussianWrap(row: Array<FloatArray>, sigma: Float): Array<FloatArray> {
            val n = row.size
            val reach = minOf(n / 2, (sigma * 3).toInt())
            val weights = FloatArray(2 * reach + 1) { exp(-((it - reach) * (it - reach)) / (2 * sigma * sigma)) }
            val total = weights.sum()
            return Array(n) { i ->
                val c = FloatArray(4)
                for (d in -reach..reach) {
                    val s = row[((i + d) % n + n) % n]
                    val w = weights[d + reach] / total
                    for (j in 0..3) c[j] += s[j] * w
                }
                c
            }
        }

        /** Premultiplied [c] times [mask], as the ARGB int a Bitmap takes. */
        fun unpremultiplied(c: FloatArray, mask: Float): Int {
            val a = c[3] * mask
            if (a <= 0.001f) return 0
            fun ch(v: Float) = (v * mask / a * 255f).toInt().coerceIn(0, 255)
            return Color.argb((a * 255f).toInt().coerceIn(0, 255), ch(c[0]), ch(c[1]), ch(c[2]))
        }
    }
}
