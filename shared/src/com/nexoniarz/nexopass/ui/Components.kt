package com.nexoniarz.nexopass.ui

import com.nexoniarz.nexopass.app.*
import com.nexoniarz.nexopass.core.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    alpha: Float = .72f,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = alpha),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun GradientButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(CircleShape)
            .background(if (enabled) BrandBrush else SolidColor(MaterialTheme.colorScheme.surfaceVariant))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun Logo(size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(BrandBrush), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Key, null, tint = Color.White, modifier = Modifier.size(size * .5f))
    }
}

private val avatarColors = listOf(Violet, Cyan, Pink, Color(0xFFF59E0B), Color(0xFF34D399), Color(0xFF60A5FA))

@Composable
fun Avatar(site: String, size: Dp) {
    val c = avatarColors[(site.hashCode() and 0x7fffffff) % avatarColors.size]
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(c, c.copy(alpha = .55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(site.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * .42f).sp)
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Surface(shape = CircleShape, color = color) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
fun EmptyState(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Key, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun modeLabel(mode: String) = NexoPass.wordCount(mode)?.let { "$it words" } ?: "${mode.removePrefix("chars")} chars"
