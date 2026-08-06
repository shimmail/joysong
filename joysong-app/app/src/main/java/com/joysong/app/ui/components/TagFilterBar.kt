package com.joysong.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joysong.app.R

@Composable
fun TagFilterBar(
    tags: List<String>,
    selectedTag: String?,
    onTagSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewAllLabel = stringResource(R.string.view_all)
    val allTags = listOf(viewAllLabel) + tags

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        allTags.forEach { tag ->
            val isAllTag = tag == viewAllLabel
            val isSelected = if (isAllTag) selectedTag == null else tag == selectedTag
            val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFFF5F5F5)
            val textColor = if (isSelected) Color.White else Color(0xFF1A1A1A)

            Text(
                text = tag,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = textColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .clickable { onTagSelected(if (isAllTag) null else tag) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}
