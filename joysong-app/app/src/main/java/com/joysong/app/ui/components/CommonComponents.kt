package com.joysong.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.joysong.app.R
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary

@Composable
fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = Primary)
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = TextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
fun EmptyView(message: String = "") {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message.ifBlank { stringResource(R.string.no_content) },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

/**
 * 用户默认头像：上方圆形（头部）+ 下方半圆（身体）的人形剪影
 */
@Composable
fun DefaultUserAvatar(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFFE0E0E0),
    iconColor: Color = Color(0xFF9E9E9E)
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawPersonSilhouette(
                iconColor = iconColor,
                centerX = w / 2f,
                headCenterY = h * 0.36f,
                headRadius = w * 0.18f,
                bodyTopY = h * 0.58f,
                bodyHalfWidth = w * 0.34f,
                bodyHeight = h * 0.42f
            )
        }
    }
}

private fun DrawScope.drawPersonSilhouette(
    iconColor: Color,
    centerX: Float,
    headCenterY: Float,
    headRadius: Float,
    bodyTopY: Float,
    bodyHalfWidth: Float,
    bodyHeight: Float
) {
    // 头部圆形
    drawCircle(
        color = iconColor,
        radius = headRadius,
        center = Offset(centerX, headCenterY)
    )
    // 身体半圆（用圆弧模拟）
    drawArc(
        color = iconColor,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(centerX - bodyHalfWidth, bodyTopY),
        size = androidx.compose.ui.geometry.Size(bodyHalfWidth * 2, bodyHeight * 2)
    )
}

@Composable
fun PlaceholderImage(
    modifier: Modifier = Modifier,
    text: String = "",
    backgroundColor: Color = SurfaceVariant
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (text.isNotBlank()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = TextPrimary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
