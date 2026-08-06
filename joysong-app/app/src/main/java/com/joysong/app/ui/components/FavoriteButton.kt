package com.joysong.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun FavoriteButton(
    isFavorited: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF1A1A1A),
    favoritedTint: Color = Color(0xFFFF9800),
    size: androidx.compose.ui.unit.Dp = 24.dp
) {
    val iconTint by animateColorAsState(
        targetValue = if (isFavorited) favoritedTint else tint,
        label = "favoriteTint"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isFavorited) 1.1f else 1f,
        label = "favoriteScale"
    )
    Icon(
        imageVector = if (isFavorited) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
        contentDescription = if (isFavorited) "已收藏" else "收藏",
        tint = iconTint,
        modifier = modifier
            .size(size)
            .scale(iconScale)
            .clickable { onClick() }
    )
}
