package com.markwhite.harbor.ui.panel

import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * The panel's materials, matching the eight FM-1 hardware colourways:
 * Dark, Orange, Gray, Dark Green, Blue, Purple, White Green and White Purple.
 */
data class Finish(
    val id: String,
    val label: String,
    val bodyTop: Color,
    val bodyBot: Color,
    /** the lit top edge of the case */
    val bodyEdge: Color,
    val tray: Color,
    val trayRim: Color,
    val silk: Color,
    val silkDim: Color,
    val capTop: Color,
    val capBot: Color,
    val capRim: Color,
    val capInk: Color,
    val naturalTop: Color,
    val naturalBot: Color,
    val naturalInk: Color,
    /** the light that leaks round a silicone key's edge */
    val keyGlow: Color,
    val sharpTop: Color,
    val sharpBot: Color,
    val knobHi: Color,
    val knobLo: Color,
    val pointer: Color,
    val accent: Color,
    /** how strongly the grain shows: dark finishes need less */
    val grain: Float,
)

val Dark = Finish(
    id = "dark", label = "Dark",
    bodyTop = Color(0xFF35363A), bodyBot = Color(0xFF1E1F22), bodyEdge = Color(0x33FFFFFF),
    tray = Color(0xFF101113), trayRim = Color(0xFF42444A),
    silk = Color(0xFFDAD7D0), silkDim = Color(0xFF84868B),
    capTop = Color(0xFF212225), capBot = Color(0xFF141517), capRim = Color(0xFF36383D), capInk = Color(0xFFEBEAE6),
    naturalTop = Color(0xFF232428), naturalBot = Color(0xFF151618), naturalInk = Color(0xFFEAEAEA), keyGlow = Color(0xFFFFA24A),
    sharpTop = Color(0xFF1C1D20), sharpBot = Color(0xFF0B0C0E),
    knobHi = Color(0xFF3B3C41), knobLo = Color(0xFF0D0E10), pointer = Color(0xFFF4F1EA), accent = Color(0xFFFFA43A),
    grain = 0.04f,
)

val Orange = Finish(
    id = "orange", label = "Orange",
    bodyTop = Color(0xFFEC5316), bodyBot = Color(0xFFCC3D0D), bodyEdge = Color(0x66FFFFFF),
    tray = Color(0xFF982D0B), trayRim = Color(0xFFF27642),
    silk = Color(0xFFF9EDE4), silkDim = Color(0xFFF5C1A8),
    capTop = Color(0xFFCC461A), capBot = Color(0xFFAD350F), capRim = Color(0xFFE06034), capInk = Color(0xFF240F07),
    naturalTop = Color(0xFFCB4418), naturalBot = Color(0xFFAD350F), naturalInk = Color(0xFF281108), keyGlow = Color(0xFFFFE6B8),
    sharpTop = Color(0xFFC44016), sharpBot = Color(0xFFA2300C),
    knobHi = Color(0xFF38393D), knobLo = Color(0xFF0D0E10), pointer = Color(0xFFF4F1EA), accent = Color(0xFFFFF1D2),
    grain = 0.06f,
)

val Gray = Finish(
    id = "gray", label = "Gray",
    bodyTop = Color(0xFFDDD8D0), bodyBot = Color(0xFFC4BEB6), bodyEdge = Color(0xB3FFFFFF),
    tray = Color(0xFF34363A), trayRim = Color(0xFFB0AAA2),
    silk = Color(0xFF36383C), silkDim = Color(0xFF7D7B76),
    capTop = Color(0xFF56585C), capBot = Color(0xFF424448), capRim = Color(0xFF6A6C70), capInk = Color(0xFFEAE8E4),
    naturalTop = Color(0xFF54565A), naturalBot = Color(0xFF404246), naturalInk = Color(0xFFDAD8D4), keyGlow = Color(0xFFFFB766),
    sharpTop = Color(0xFF4B4D51), sharpBot = Color(0xFF37393D),
    knobHi = Color(0xFF3F4045), knobLo = Color(0xFF101113), pointer = Color(0xFFF4F1EA), accent = Color(0xFFF08A1C),
    grain = 0.07f,
)

val DarkGreen = Finish(
    id = "dark_green", label = "Dark Green",
    bodyTop = Color(0xFF35373A), bodyBot = Color(0xFF1F2124), bodyEdge = Color(0x33FFFFFF),
    tray = Color(0xFF3E9E86), trayRim = Color(0xFF4E5156),
    silk = Color(0xFFDAD7D0), silkDim = Color(0xFF8A8C91),
    capTop = Color(0xFF71D8BD), capBot = Color(0xFF55BD9F), capRim = Color(0xFF8BE4CD), capInk = Color(0xFF123029),
    naturalTop = Color(0xFF6CD4B9), naturalBot = Color(0xFF51B89E), naturalInk = Color(0xFF123029), keyGlow = Color(0xFFB5FFE9),
    sharpTop = Color(0xFF61C7AC), sharpBot = Color(0xFF46A78E),
    knobHi = Color(0xFF3B3C41), knobLo = Color(0xFF0E0F11), pointer = Color(0xFFF4F1EA), accent = Color(0xFF72F2CE),
    grain = 0.04f,
)

val Blue = Finish(
    id = "blue", label = "Blue",
    bodyTop = Color(0xFFF3F1EC), bodyBot = Color(0xFFDEDCD6), bodyEdge = Color(0xB3FFFFFF),
    tray = Color(0xFF34506B), trayRim = Color(0xFFC5C3BC),
    silk = Color(0xFF3A3B3F), silkDim = Color(0xFF84837E),
    capTop = Color(0xFF587FA3), capBot = Color(0xFF436586), capRim = Color(0xFF6D94B8), capInk = Color(0xFFF0F5FA),
    naturalTop = Color(0xFF547A9C), naturalBot = Color(0xFF406180), naturalInk = Color(0xFF132230), keyGlow = Color(0xFF9AD0FF),
    sharpTop = Color(0xFF4B6F90), sharpBot = Color(0xFF365573),
    knobHi = Color(0xFF3B3D42), knobLo = Color(0xFF0F1013), pointer = Color(0xFFF4F1EA), accent = Color(0xFF66B6FF),
    grain = 0.06f,
)

val Purple = Finish(
    id = "purple", label = "Purple",
    bodyTop = Color(0xFFD895CD), bodyBot = Color(0xFFBA76B0), bodyEdge = Color(0x66FFFFFF),
    tray = Color(0xFF684880), trayRim = Color(0xFFE3ACE0),
    silk = Color(0xFF361F42), silkDim = Color(0xFF71497A),
    capTop = Color(0xFF9D78B6), capBot = Color(0xFF825E9B), capRim = Color(0xFFB28FCA), capInk = Color(0xFF231330),
    naturalTop = Color(0xFF9772B0), naturalBot = Color(0xFF7D5B96), naturalInk = Color(0xFF251633), keyGlow = Color(0xFFF2C2FF),
    sharpTop = Color(0xFF8D68A6), sharpBot = Color(0xFF72508B),
    knobHi = Color(0xFF3B3842), knobLo = Color(0xFF0E0D12), pointer = Color(0xFFF4F1EA), accent = Color(0xFFFFB2F5),
    grain = 0.05f,
)

val WhiteGreen = Finish(
    id = "white_green", label = "White Green",
    bodyTop = Color(0xFFF5F4EE), bodyBot = Color(0xFFE0DFD8), bodyEdge = Color(0xB3FFFFFF),
    tray = Color(0xFF317F7B), trayRim = Color(0xFFC8C7BF),
    silk = Color(0xFF36383B), silkDim = Color(0xFF83847F),
    capTop = Color(0xFF5DC0BB), capBot = Color(0xFF42A19C), capRim = Color(0xFF75D2CD), capInk = Color(0xFF102E2D),
    naturalTop = Color(0xFF57B7B2), naturalBot = Color(0xFF3E9994), naturalInk = Color(0xFF102E2D), keyGlow = Color(0xFFA8FFF9),
    sharpTop = Color(0xFF4DA9A4), sharpBot = Color(0xFF358A85),
    knobHi = Color(0xFF3B3D42), knobLo = Color(0xFF0F1013), pointer = Color(0xFFF4F1EA), accent = Color(0xFF5CE0D8),
    grain = 0.06f,
)

val WhitePurple = Finish(
    id = "white_purple", label = "White Purple",
    bodyTop = Color(0xFFDFDFDE), bodyBot = Color(0xFFC9C9C7), bodyEdge = Color(0xB3FFFFFF),
    tray = Color(0xFF4E3573), trayRim = Color(0xFFB5B5B2),
    silk = Color(0xFF36383B), silkDim = Color(0xFF7E7E82),
    capTop = Color(0xFF8161AF), capBot = Color(0xFF674993), capRim = Color(0xFF9779C4), capInk = Color(0xFFF3EFFA),
    naturalTop = Color(0xFF7B5BA8), naturalBot = Color(0xFF62458C), naturalInk = Color(0xFF1F1233), keyGlow = Color(0xFFD2B8FF),
    sharpTop = Color(0xFF70519C), sharpBot = Color(0xFF563B7E),
    knobHi = Color(0xFF3B3A42), knobLo = Color(0xFF0E0E12), pointer = Color(0xFFF4F1EA), accent = Color(0xFFB892FF),
    grain = 0.06f,
)

val Finishes = listOf(Dark, Orange, Gray, DarkGreen, Blue, Purple, WhiteGreen, WhitePurple)

fun finishOf(id: String): Finish = when (id) {
    "graphite", "dusk", "dark" -> Dark
    "arctic", "gray" -> Gray
    "navy", "blue" -> Blue
    "green", "dark_green" -> DarkGreen
    "red", "orange" -> Orange
    "purple" -> Purple
    "white_green" -> WhiteGreen
    "white_purple" -> WhitePurple
    else -> Dark
}

/** Derives keybed & tray shades when the user picks a custom general colour for the keys. */
fun Finish.withKeysColor(color: Color?): Finish {
    if (color == null) return this
    val ink = if (color.luminance() > 0.35f) Color(0xFF18191C) else Color(0xFFEDEDEC)
    return copy(
        naturalTop = color,
        naturalBot = lerp(color, Color.Black, 0.16f),
        naturalInk = ink,
        sharpTop = lerp(color, Color.Black, 0.10f),
        sharpBot = lerp(color, Color.Black, 0.28f),
        tray = lerp(color, Color.Black, 0.38f),
        trayRim = lerp(color, Color.White, 0.18f),
        keyGlow = lerp(color, Color.White, 0.45f),
    )
}

/** Derives 3D knob shading and a contrasting pointer for an optional custom knob colour. */
fun knobShade(base: Finish, custom: Color?): Triple<Color, Color, Color> {
    if (custom == null) return Triple(base.knobHi, base.knobLo, base.pointer)
    val hi = lerp(custom, Color.White, 0.18f)
    val lo = lerp(custom, Color.Black, 0.50f)
    val ptr = if (custom.luminance() > 0.50f) Color(0xFF1C1D20) else Color(0xFFF4F1EA)
    return Triple(hi, lo, ptr)
}

val LocalFinish = staticCompositionLocalOf { Dark }

// ------------------------------------------------------------------------------ light

val LedAmber = Color(0xFFFFA43A)
val LedAmberHot = Color(0xFFFFE3B0)
val LedGreen = Color(0xFF6BE07A)
val LedGreenHot = Color(0xFFD8FFDC)

/** A soft shadow under a rounded rect, blurred on the render thread (BlurMaskFilter, API 28+). */
fun DrawScope.softShadow(topLeft: Offset, size: Size, radius: Float, blur: Float, color: Color) {
    if (blur <= 0f) return
    val paint = shadowPaint
    paint.color = color.toArgb()
    paint.maskFilter = blurFilter(blur)
    drawContext.canvas.nativeCanvas.drawRoundRect(
        topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height, radius, radius, paint,
    )
}

/** A line that glows: [color] blurred by [blur] px, for lit pointers and arcs. */
fun DrawScope.glowLine(from: Offset, to: Offset, width: Float, blur: Float, color: Color) {
    val paint = shadowPaint
    paint.color = color.toArgb()
    paint.maskFilter = blurFilter(blur)
    paint.strokeWidth = width
    paint.strokeCap = android.graphics.Paint.Cap.ROUND
    drawContext.canvas.nativeCanvas.drawLine(from.x, from.y, to.x, to.y, paint)
}

/** The same for an arc (degrees, clockwise from three o'clock). */
fun DrawScope.glowArc(center: Offset, radius: Float, start: Float, sweep: Float, width: Float, blur: Float, color: Color) {
    if (sweep <= 0f) return
    val paint = shadowPaint
    paint.color = color.toArgb()
    paint.maskFilter = blurFilter(blur)
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = width
    paint.strokeCap = android.graphics.Paint.Cap.ROUND
    drawContext.canvas.nativeCanvas.drawArc(center.x - radius, center.y - radius, center.x + radius, center.y + radius, start, sweep, false, paint)
    paint.style = android.graphics.Paint.Style.FILL
}

/** The same for a disc. */
fun DrawScope.softShadow(center: Offset, radius: Float, blur: Float, color: Color) {
    val paint = shadowPaint
    paint.color = color.toArgb()
    paint.maskFilter = blurFilter(blur)
    drawContext.canvas.nativeCanvas.drawCircle(center.x, center.y, radius, paint)
}

/** A light's bloom: a radial falloff from [color] to nothing. */
fun DrawScope.bloom(center: Offset, radius: Float, color: Color, alpha: Float) {
    if (alpha <= 0.01f || radius <= 0f) return
    drawCircle(
        Brush.radialGradient(
            0f to color.copy(alpha = alpha), 0.35f to color.copy(alpha = alpha * 0.45f), 1f to color.copy(alpha = 0f),
            center = center, radius = radius,
        ),
        radius, center,
    )
}

private val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
private val filters = HashMap<Int, BlurMaskFilter>()
private fun blurFilter(blur: Float): BlurMaskFilter = filters.getOrPut((blur * 4).toInt().coerceAtLeast(1)) {
    BlurMaskFilter(blur.coerceAtLeast(0.25f), BlurMaskFilter.Blur.NORMAL)
}

/** Fine monochrome noise, tiled over the case so it reads as bead-blasted aluminium. */
private val grainBrush: ShaderBrush by lazy {
    val n = 160
    val rnd = Random(7)
    val px = IntArray(n * n) {
        val v = rnd.nextInt(256)
        android.graphics.Color.argb(255, v, v, v)
    }
    val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

fun DrawScope.grain(alpha: Float) {
    drawRect(grainBrush, alpha = alpha, blendMode = androidx.compose.ui.graphics.BlendMode.Overlay)
}

// ------------------------------------------------------------------------------ surfaces

/**
 * The case: a lit top edge, a gradient from the light above, grain, and a shadow on the desk.
 * [corner] 0 runs it edge to edge.
 */
fun Modifier.caseBody(finish: Finish, corner: Dp = 26.dp): Modifier {
    val shape = RoundedCornerShape(corner)
    val lifted = if (corner > 0.dp) dropShadow(shape, Shadow(radius = 24.dp, color = Color.Black, offset = DpOffset(0.dp, 10.dp), alpha = 0.55f)) else this
    return lifted
        .drawBehind {
            val cr = CornerRadius(corner.toPx())
            drawRoundRect(Brush.verticalGradient(listOf(finish.bodyTop, finish.bodyBot)), cornerRadius = cr)
            // the lamp above: a soft highlight across the upper third
            drawRoundRect(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.35f, -size.height * 0.05f), radius = size.maxDimension * 0.8f,
                ),
                cornerRadius = cr,
            )
            grain(finish.grain)
            // chamfer: a bright line along the top, a dark one along the bottom (not when full-bleed)
            if (corner > 0.dp) drawRoundRect(
                Brush.verticalGradient(0f to finish.bodyEdge, 0.08f to Color.Transparent, 0.92f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f)),
                cornerRadius = cr, style = Stroke(1.2.dp.toPx()),
            )
        }
}

/** A recessed well in the case: darker, shadowed from the top edge, lit along the bottom lip. */
fun Modifier.tray(finish: Finish, corner: Dp = 14.dp): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .drawBehind {
            val cr = CornerRadius(corner.toPx())
            drawRoundRect(finish.tray, cornerRadius = cr)
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), finish.trayRim)),
                cornerRadius = cr, style = Stroke(1.dp.toPx()),
            )
        }
        .innerShadow(shape, Shadow(radius = 10.dp, color = Color.Black, offset = DpOffset(0.dp, 4.dp), alpha = 0.75f))
}
