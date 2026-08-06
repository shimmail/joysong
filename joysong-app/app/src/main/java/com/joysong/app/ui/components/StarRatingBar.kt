package com.joysong.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.SurfaceVariant

@Composable
fun StarRatingBar(
    rating: Int,
    onRatingChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxStars: Int = 5,
    readOnly: Boolean = false,
    starSize: Dp = 40.dp
) {
    Row(modifier = modifier) {
        for (i in 1..maxStars) {
            Icon(
                imageVector = if (i <= rating) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = "Star $i",
                tint = if (i <= rating) Primary else SurfaceVariant,
                modifier = Modifier
                    .size(starSize)
                    .then(
                        if (!readOnly) Modifier.clickable { onRatingChanged(i) }
                        else Modifier
                    )
            )
        }
    }
}
