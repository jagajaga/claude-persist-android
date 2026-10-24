package me.jagajaga.persist.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * The panel's look, carried over: the same light editor surface, the blue
 * focus border on your own messages and the composer, white tool cards with a
 * green status dot, and quiet grey turn footers.
 */
object Palette {
    val page = Color(0xFFF8F8F8)
    val surface = Color(0xFFFFFFFF)
    val text = Color(0xFF1F1F1F)
    val muted = Color(0xFF6B6B6B)
    val faint = Color(0xFF9A9A9A)
    val border = Color(0xFFDADADA)
    val accent = Color(0xFF1E6FD9)
    val accentSoft = Color(0xFFE8F0FC)
    val ok = Color(0xFF2E7D32)
    val okSoft = Color(0xFFE8F5E9)
    val error = Color(0xFFC62828)
    val errorSoft = Color(0xFFFDECEA)
    val warn = Color(0xFFB26A00)
    val codeBg = Color(0xFFF2F2F2)
}

val Mono = FontFamily.Monospace

object Type {
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = Palette.text)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Palette.muted)
    val tiny = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = Palette.faint)
    val code = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontFamily = Mono, color = Palette.text)
    val title = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = Palette.text)
}

@Composable
fun PersistTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.accent,
            onPrimary = Color.White,
            background = Palette.page,
            surface = Palette.surface,
            onSurface = Palette.text,
            onBackground = Palette.text,
            error = Palette.error,
            outline = Palette.border,
        ),
        content = content,
    )
}
