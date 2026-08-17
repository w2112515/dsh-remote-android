package dev.dshremote.gate0c.ui.v2

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** v2 palette mirrors the full-vision prototype tokens (docs/product/PROTOTYPE_V2_FACT_MAPPING.md). */
internal data class V2Palette(
    val bg: Color,
    val bg2: Color,
    val card: Color,
    val card2: Color,
    val inset: Color,
    val line: Color,
    val tx: Color,
    val tx2: Color,
    val tx3: Color,
    val blue: Color,
    val cyan: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
)

internal val V2Dark = V2Palette(
    bg = Color(0xFF050810),
    bg2 = Color(0xFF0C1220),
    card = Color(0xFF0F1526),
    card2 = Color(0xFF131B31),
    inset = Color(0xFF080D19),
    line = Color(0x1A7896FF),
    tx = Color(0xFFE8ECF5),
    tx2 = Color(0xFF96A0B8),
    tx3 = Color(0xFF5B657F),
    blue = Color(0xFF4D6BFE),
    cyan = Color(0xFF22D3EE),
    green = Color(0xFF34D399),
    amber = Color(0xFFFBBF24),
    red = Color(0xFFF87171),
)

internal val V2Light = V2Palette(
    bg = Color(0xFFF5F7FA),
    bg2 = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    card2 = Color(0xFFF0F3F9),
    inset = Color(0xFFEDF1F6),
    line = Color(0x140A2A8A),
    tx = Color(0xFF17202B),
    tx2 = Color(0xFF4A5872),
    tx3 = Color(0xFF8A94A8),
    blue = Color(0xFF3B5BEE),
    cyan = Color(0xFF0E9CB5),
    green = Color(0xFF1F9E6E),
    amber = Color(0xFFB07E10),
    red = Color(0xFFD0433B),
)

internal val LocalV2 = staticCompositionLocalOf { V2Dark }

@Composable
internal fun V2Theme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalV2 provides if (isSystemInDarkTheme()) V2Dark else V2Light,
        content = content,
    )
}
