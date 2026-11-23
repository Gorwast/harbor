package com.markwhite.harbor.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markwhite.harbor.data.Settings
import com.markwhite.harbor.ui.panel.Finish
import com.markwhite.harbor.ui.panel.Finishes
import com.markwhite.harbor.ui.panel.Fm1Panel
import com.markwhite.harbor.ui.panel.KeybedGeometry
import com.markwhite.harbor.ui.panel.ONE_ROW
import com.markwhite.harbor.ui.panel.finishOf
import com.markwhite.harbor.ui.panel.knobShade
import com.markwhite.harbor.ui.panel.withButtonsColor
import com.markwhite.harbor.ui.panel.withCaseColor
import com.markwhite.harbor.ui.panel.withKeysColor
import com.markwhite.harbor.ui.theme.Tokens

private sealed interface ColorTarget {
    data object Case : ColorTarget
    data object Buttons : ColorTarget
    data object Keys : ColorTarget
    data class Knob(val key: String, val label: String) : ColorTarget
}

private val PANEL_KNOBS = listOf(
    "master" to "MASTER",
    "enc${Fm1Panel.SELECT.id}" to Fm1Panel.SELECT.short,
    "enc${Fm1Panel.PRESETS.id}" to Fm1Panel.PRESETS.short,
    "enc${Fm1Panel.ALGORITHM.id}" to Fm1Panel.ALGORITHM.short,
) + Fm1Panel.KNOBS.map { "enc${it.id}" to it.short }

private val CURATED_COLORS = listOf(
    Color(0xFF26272B), // Charcoal
    Color(0xFF54565A), // Slate
    Color(0xFFF2EFE9), // Warm White
    Color(0xFFEC5316), // Orange
    Color(0xFFD94343), // Coral Red
    Color(0xFFF0A030), // Amber
    Color(0xFF6CD4B9), // Mint
    Color(0xFF42A19C), // Teal
    Color(0xFF58A6D8), // Sky
    Color(0xFF436586), // Steel Blue
    Color(0xFF8161AF), // Violet
    Color(0xFFD86CC2), // Magenta
)

/**
 * Shared panel colour customiser used in Display settings (with [showPreview] = true)
 * and the Play screen's live bottom sheet ([showPreview] = false).
 */
@Composable
fun PanelAppearanceControls(settings: Settings, showPreview: Boolean) {
    val finishId by settings.finish.collectAsState()
    val caseArgb by settings.caseColor.collectAsState()
    val buttonsArgb by settings.buttonsColor.collectAsState()
    val keysArgb by settings.keysColor.collectAsState()
    val knobColorsArgb by settings.knobColors.collectAsState()

    val baseFinish = finishOf(finishId)
    val resolvedFinish = baseFinish
        .withCaseColor(caseArgb?.let(::Color))
        .withButtonsColor(buttonsArgb?.let(::Color))
        .withKeysColor(keysArgb?.let(::Color))
    val resolvedKnobColors = remember(knobColorsArgb) { knobColorsArgb.mapValues { Color(it.value) } }

    var pickingTarget by remember { mutableStateOf<ColorTarget?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showPreview) {
            MiniPanelPreview(resolvedFinish, resolvedKnobColors)
        }

        SwatchChoice(
            "Panel finish",
            Finishes.map { Swatch(it.id, it.label, it.bodyTop, it.naturalTop, it.trayRim) },
            baseFinish.id,
            settings::setFinish,
        )

        ColorOverrideRow(
            title = "Case colour",
            shown = resolvedFinish.bodyTop,
            isCustom = caseArgb != null,
            presetLabel = baseFinish.label,
            onClick = { pickingTarget = ColorTarget.Case },
        )

        ColorOverrideRow(
            title = "Buttons colour",
            shown = resolvedFinish.capTop,
            isCustom = buttonsArgb != null,
            presetLabel = baseFinish.label,
            onClick = { pickingTarget = ColorTarget.Buttons },
        )

        ColorOverrideRow(
            title = "Keys colour",
            shown = resolvedFinish.naturalTop,
            isCustom = keysArgb != null,
            presetLabel = baseFinish.label,
            onClick = { pickingTarget = ColorTarget.Keys },
        )

        KnobColorsPicker(
            activeFinish = resolvedFinish,
            knobColors = knobColorsArgb,
            onPickKnob = { key, label -> pickingTarget = ColorTarget.Knob(key, label) },
            onResetAll = { settings.setAllKnobColors(null, PANEL_KNOBS.map { it.first }) },
        )
    }

    pickingTarget?.let { target ->
        val initialColor = when (target) {
            ColorTarget.Case -> caseArgb?.let(::Color)
            ColorTarget.Buttons -> buttonsArgb?.let(::Color)
            ColorTarget.Keys -> keysArgb?.let(::Color)
            is ColorTarget.Knob -> knobColorsArgb[target.key]?.let(::Color)
        }
        val fallbackColor = when (target) {
            ColorTarget.Case -> baseFinish.bodyTop
            ColorTarget.Buttons -> baseFinish.capTop
            ColorTarget.Keys -> baseFinish.naturalTop
            is ColorTarget.Knob -> baseFinish.knobHi
        }
        ColorPickerDialog(
            title = when (target) {
                ColorTarget.Case -> "Case colour"
                ColorTarget.Buttons -> "Buttons colour"
                ColorTarget.Keys -> "Keys colour"
                is ColorTarget.Knob -> "${target.label} colour"
            },
            initial = initialColor,
            fallback = fallbackColor,
            showApplyAll = target is ColorTarget.Knob,
            onApplyAll = { color ->
                settings.setAllKnobColors(color?.toArgb(), PANEL_KNOBS.map { it.first })
                pickingTarget = null
            },
            onConfirm = { color ->
                when (target) {
                    ColorTarget.Case -> settings.setCaseColor(color?.toArgb())
                    ColorTarget.Buttons -> settings.setButtonsColor(color?.toArgb())
                    ColorTarget.Keys -> settings.setKeysColor(color?.toArgb())
                    is ColorTarget.Knob -> settings.setKnobColor(target.key, color?.toArgb())
                }
                pickingTarget = null
            },
            onDismiss = { pickingTarget = null },
        )
    }
}

/** Live miniature FM-1 panel silhouette for Display settings. */
@Composable
fun MiniPanelPreview(finish: Finish, knobColors: Map<String, Color>, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(Fm1Panel.W / Fm1Panel.H)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Tokens.line.copy(alpha = 0.7f), RoundedCornerShape(14.dp)),
    ) {
        val sx = size.width / Fm1Panel.W
        val sy = size.height / Fm1Panel.H
        val cr = CornerRadius(14.dp.toPx())
        drawRoundRect(Brush.verticalGradient(listOf(finish.bodyTop, finish.bodyBot)), cornerRadius = cr)
        drawRoundRect(finish.bodyEdge, cornerRadius = cr, style = Stroke(1.dp.toPx()))

        // LCD bezel & dark screen
        val lcdX = Fm1Panel.LCD_X * sx
        val lcdY = Fm1Panel.LCD_Y * sy
        val lcdS = Fm1Panel.LCD_SIZE * sx
        drawRoundRect(Color(0xFF101113), Offset(lcdX - 8 * sx, lcdY - 8 * sy), Size(lcdS + 16 * sx, lcdS + 16 * sy), CornerRadius(10 * sx))
        drawRoundRect(Color(0xFF1A2226), Offset(lcdX, lcdY), Size(lcdS, lcdS), CornerRadius(4 * sx))
        drawLine(finish.accent.copy(alpha = 0.7f), Offset(lcdX + lcdS * 0.14f, lcdY + lcdS * 0.78f), Offset(lcdX + lcdS * 0.86f, lcdY + lcdS * 0.78f), strokeWidth = 2.dp.toPx())

        // Button tray + 14 buttons
        drawRoundRect(finish.tray, Offset(896f * sx, 222f * sy), Size(358f * sx, 160f * sy), CornerRadius(8 * sx))
        drawRoundRect(finish.tray, Offset(52f * sx, 388f * sy), Size(256f * sx, 64f * sy), CornerRadius(8 * sx))
        for (b in Fm1Panel.BUTTONS) {
            val tl = Offset(b.x * sx, b.y * sy)
            val sz = Size(b.w * sx, b.h * sy)
            drawRoundRect(Brush.verticalGradient(listOf(finish.capTop, finish.capBot), startY = tl.y, endY = tl.y + sz.height), tl, sz, CornerRadius(4 * sx))
            drawRoundRect(finish.capRim, tl, sz, CornerRadius(4 * sx), style = Stroke(0.7.dp.toPx()))
        }

        // Keybed tray + 27 keys
        val trayLeft = 48f * sx
        val trayTop = 556f * sy
        val trayW = 1184f * sx
        val trayH = 244f * sy
        drawRoundRect(finish.tray, Offset(trayLeft, trayTop), Size(trayW, trayH), CornerRadius(10 * sx))
        val geo = KeybedGeometry(ONE_ROW, trayW - 16 * sx, trayH - 16 * sy)
        val ox = trayLeft + 8 * sx
        val oy = trayTop + 8 * sy
        for (k in geo.drawOrder()) {
            val tl = Offset(ox + k.left + 1f, oy + k.top)
            val sz = Size((k.right - k.left - 2f).coerceAtLeast(2f), (k.bottom - k.top).coerceAtLeast(4f))
            val brush = if (k.black) {
                Brush.verticalGradient(listOf(finish.sharpTop, finish.sharpBot), startY = tl.y, endY = tl.y + sz.height)
            } else {
                Brush.verticalGradient(listOf(finish.naturalTop, finish.naturalBot), startY = tl.y, endY = tl.y + sz.height)
            }
            drawRoundRect(brush, tl, sz, CornerRadius(3 * sx))
        }

        // All 8 knobs in their individual colours
        val knobs = listOf(
            Triple("master", Offset(Fm1Panel.POT_X * sx, Fm1Panel.POT_Y * sy), Fm1Panel.POT_R * sx),
            Triple("enc${Fm1Panel.SELECT.id}", Offset(Fm1Panel.SELECT.x * sx, Fm1Panel.SELECT.y * sy), Fm1Panel.SELECT.r * sx),
            Triple("enc${Fm1Panel.PRESETS.id}", Offset(Fm1Panel.PRESETS.x * sx, Fm1Panel.PRESETS.y * sy), Fm1Panel.PRESETS.r * sx),
            Triple("enc${Fm1Panel.ALGORITHM.id}", Offset(Fm1Panel.ALGORITHM.x * sx, Fm1Panel.ALGORITHM.y * sy), Fm1Panel.ALGORITHM.r * sx),
        ) + Fm1Panel.KNOBS.map { Triple("enc${it.id}", Offset(it.x * sx, it.y * sy), it.r * sx) }
        for ((key, center, r) in knobs) {
            val (hi, lo, ptr) = knobShade(finish, knobColors[key])
            drawCircle(Color.Black.copy(alpha = 0.45f), r * 1.05f, Offset(center.x, center.y + r * 0.12f))
            drawCircle(Brush.verticalGradient(listOf(hi, lo), startY = center.y - r, endY = center.y + r), r, center)
            drawLine(ptr, Offset(center.x, center.y - r * 0.25f), Offset(center.x, center.y - r * 0.78f), strokeWidth = (r * 0.16f).coerceAtLeast(1.5f))
        }
    }
}

@Composable
private fun ColorOverrideRow(
    title: String,
    shown: Color,
    isCustom: Boolean,
    presetLabel: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(shown)
                .border(1.5.dp, if (isCustom) Tokens.amber else Tokens.line, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (isCustom) "Custom" else "Preset ($presetLabel)",
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.dim,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Tokens.dim)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnobColorsPicker(
    activeFinish: Finish,
    knobColors: Map<String, Int>,
    onPickKnob: (key: String, label: String) -> Unit,
    onResetAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Knob colours", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (knobColors.isNotEmpty()) {
                TextButton(onClick = onResetAll) { Text("Reset all") }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((key, label) in PANEL_KNOBS) {
                val custom = knobColors[key]?.let(::Color)
                val (hi, lo, _) = knobShade(activeFinish, custom)
                Column(
                    Modifier.width(64.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { onPickKnob(key, label) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(36.dp)
                            .border(2.dp, if (custom != null) Tokens.amber else Color.Transparent, CircleShape)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(Brush.verticalGradient(listOf(hi, lo)))
                            .border(1.dp, Tokens.line, CircleShape),
                    )
                    Text(
                        label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (custom != null) Tokens.amberHi else Tokens.dim,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerDialog(
    title: String,
    initial: Color?,
    fallback: Color,
    showApplyAll: Boolean,
    onApplyAll: (Color?) -> Unit,
    onConfirm: (Color?) -> Unit,
    onDismiss: () -> Unit,
) {
    val startColor = initial ?: fallback
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(startColor.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var valV by remember { mutableFloatStateOf(hsv[2]) }
    var usePreset by remember { mutableStateOf(initial == null) }

    val currentColor = remember(hue, sat, valV) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, valV)))
    }
    val selectedOrNull: Color? = if (usePreset) null else currentColor

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(selectedOrNull ?: fallback)
                            .border(1.5.dp, Tokens.line, CircleShape),
                    )
                    Text(
                        if (usePreset) "Using preset colour" else "Custom colour",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.dim,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        usePreset = true
                        val resetHsv = FloatArray(3)
                        android.graphics.Color.colorToHSV(fallback.toArgb(), resetHsv)
                        hue = resetHsv[0]; sat = resetHsv[1]; valV = resetHsv[2]
                    }) { Text("Reset") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in CURATED_COLORS) {
                        val isMatch = !usePreset && c.toArgb() == currentColor.toArgb()
                        Box(
                            Modifier.size(32.dp)
                                .border(2.dp, if (isMatch) Tokens.amber else Color.Transparent, CircleShape)
                                .padding(3.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(1.dp, Tokens.line, CircleShape)
                                .clickable {
                                    usePreset = false
                                    val cHsv = FloatArray(3)
                                    android.graphics.Color.colorToHSV(c.toArgb(), cHsv)
                                    hue = cHsv[0]; sat = cHsv[1]; valV = cHsv[2]
                                },
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Hue", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                    Slider(
                        value = hue,
                        onValueChange = { usePreset = false; hue = it },
                        valueRange = 0f..360f,
                        colors = SliderDefaults.colors(thumbColor = Tokens.amber, activeTrackColor = Tokens.amber),
                    )
                    Text("Saturation", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                    Slider(
                        value = sat,
                        onValueChange = { usePreset = false; sat = it },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Tokens.amber, activeTrackColor = Tokens.amber),
                    )
                    Text("Brightness", style = MaterialTheme.typography.labelSmall, color = Tokens.dim)
                    Slider(
                        value = valV,
                        onValueChange = { usePreset = false; valV = it },
                        valueRange = 0.12f..1f,
                        colors = SliderDefaults.colors(thumbColor = Tokens.amber, activeTrackColor = Tokens.amber),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showApplyAll) {
                    TextButton(onClick = { onApplyAll(selectedOrNull) }) { Text("All knobs") }
                }
                Button(onClick = { onConfirm(selectedOrNull) }) { Text("Done") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
