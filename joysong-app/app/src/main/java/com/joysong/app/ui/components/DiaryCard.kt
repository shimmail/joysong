package com.joysong.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.joysong.app.R
import com.joysong.app.domain.model.Diary
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary

enum class DiaryCardStyle {
    HORIZONTAL,  // 横向卡片（首页用）
    VERTICAL     // 纵向列表卡片（其他页面用）
}

@Composable
fun DiaryCard(
    diary: Diary,
    style: DiaryCardStyle = DiaryCardStyle.VERTICAL,
    onClick: () -> Unit = {},
    showMenu: Boolean = false,
    onEditClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    onReportClick: (() -> Unit)? = null,
    showBeforeAfter: Boolean = true,
    showProjectTag: Boolean = true,
    showStats: Boolean = true,
    showShare: Boolean = true,
    maxContentLines: Int = 2,
    modifier: Modifier = Modifier
) {
    when (style) {
        DiaryCardStyle.HORIZONTAL -> HorizontalDiaryCard(diary = diary, onClick = onClick, modifier = modifier)
        DiaryCardStyle.VERTICAL -> VerticalDiaryCard(
            diary = diary,
            onClick = onClick,
            showMenu = showMenu,
            onEditClick = onEditClick,
            onDeleteClick = onDeleteClick,
            onReportClick = onReportClick,
            showBeforeAfter = showBeforeAfter,
            showProjectTag = showProjectTag,
            showStats = showStats,
            showShare = showShare,
            maxContentLines = maxContentLines,
            modifier = modifier
        )
    }
}

@Composable
private fun VerticalDiaryCard(
    diary: Diary,
    onClick: () -> Unit,
    showMenu: Boolean,
    onEditClick: (() -> Unit)?,
    onDeleteClick: (() -> Unit)?,
    onReportClick: (() -> Unit)?,
    showBeforeAfter: Boolean,
    showProjectTag: Boolean,
    showStats: Boolean,
    showShare: Boolean,
    maxContentLines: Int,
    modifier: Modifier = Modifier
) {
    val beforeImages = remember(diary.beforeImages) {
        diary.beforeImages.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    val afterImages = remember(diary.afterImages) {
        diary.afterImages.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column {
            // === Top: Author info row ===
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular avatar
                if (diary.authorAvatar.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(diary.authorAvatar)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    DefaultUserAvatar(
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = diary.authorName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = diary.publishDate,
                        fontSize = 12.sp,
                        color = TextHint
                    )
                }
                // Right: rating
                if (diary.rating > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${diary.rating}.0",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }
            }

            // === Before/After comparison images ===
            if (showBeforeAfter && (beforeImages.isNotEmpty() || afterImages.isNotEmpty())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (beforeImages.isNotEmpty()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.before_label),
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(beforeImages.first())
                                    .crossfade(true)
                                    .build(),
                                contentDescription = stringResource(R.string.before_photos_label),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    if (afterImages.isNotEmpty()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.after_label),
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(afterImages.first())
                                    .crossfade(true)
                                    .build(),
                                contentDescription = stringResource(R.string.after_photos_label),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // === Diary title ===
            if (diary.title.isNotBlank()) {
                Text(
                    text = diary.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // === Project tag + content summary ===
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                if (showProjectTag && diary.projectName.isNotBlank()) {
                    Text(
                        text = "#${diary.projectName}#",
                        fontSize = 13.sp,
                        color = PrimaryDark,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (diary.content.isNotBlank()) {
                    Text(
                        text = diary.content,
                        fontSize = 14.sp,
                        color = TextSecondary,
                        maxLines = maxContentLines,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // === Bottom: interaction stats row (optional) ===
            if (showStats || showMenu) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (showStats) {
                        DiaryStatItem(
                            icon = Icons.Outlined.FavoriteBorder,
                            count = diary.likeCount
                        )
                        DiaryStatItem(
                            icon = Icons.Outlined.BookmarkBorder,
                            count = diary.favoriteCount
                        )
                        DiaryStatItem(
                            icon = Icons.Outlined.ChatBubbleOutline,
                            count = diary.commentCount
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // 分享按钮
                    if (showShare) {
                        val context = LocalContext.current
                        IconButton(onClick = {
                            val shareText = buildDiaryShareText(diary)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, diary.title)
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = stringResource(R.string.share_diary),
                                tint = TextHint,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    // More menu (optional)
                    if ((showMenu && onEditClick != null && onDeleteClick != null) || onReportClick != null) {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = null,
                                    tint = TextHint
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                if (showMenu && onEditClick != null) {
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(R.string.edit)) },
                                        onClick = {
                                            menuExpanded = false
                                            onEditClick()
                                        }
                                    )
                                }
                                if (showMenu && onDeleteClick != null) {
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(R.string.delete)) },
                                        onClick = {
                                            menuExpanded = false
                                            onDeleteClick()
                                        }
                                    )
                                }
                                if (onReportClick != null) {
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(R.string.report)) },
                                        onClick = {
                                            menuExpanded = false
                                            onReportClick()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HorizontalDiaryCard(diary: Diary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .width(200.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column {
            if (diary.coverImage.isNotBlank()) {
                AsyncImage(
                    model = diary.coverImage,
                    contentDescription = diary.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                PlaceholderImage(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    text = stringResource(R.string.diary_placeholder)
                )
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = diary.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (diary.authorAvatar.isNotBlank()) {
                        AsyncImage(
                            model = diary.authorAvatar,
                            contentDescription = diary.authorName,
                            modifier = Modifier.size(20.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        DefaultUserAvatar(
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = diary.authorName,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = TextHint
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "${diary.likeCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextHint
                    )
                }
            }
        }
    }
}

@Composable
private fun DiaryStatItem(
    icon: ImageVector,
    count: Int
) {
    Box(
        modifier = Modifier.width(36.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = TextHint
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = formatDiaryCount(count),
                fontSize = 12.sp,
                color = TextHint,
                maxLines = 1
            )
        }
    }
}

private fun formatDiaryCount(count: Int): String {
    return when {
        count >= 10000 -> "${count / 10000}.${(count % 10000) / 1000}w"
        count >= 1000 -> "${count / 1000}.${(count % 1000) / 100}k"
        else -> count.toString()
    }
}

/**
 * 构建日记分享文本，包含标题、内容、项目、医生、机构及术前术后图片
 */
fun buildDiaryShareText(diary: Diary): String = buildString {
    // 标题
    append("📝 ").append(diary.title).append("\n\n")

    // 内容
    if (diary.content.isNotBlank()) {
        val content = if (diary.content.length > 200) diary.content.take(200) + "..." else diary.content
        append(content).append("\n\n")
    }

    // 关联信息
    val info = mutableListOf<String>()
    if (diary.projectName.isNotBlank()) info.add("🏷 项目：${diary.projectName}")
    if (diary.doctorName.isNotBlank()) info.add("👨‍⚕️ 医生：${diary.doctorName}")
    if (diary.institutionName.isNotBlank()) info.add("🏥 机构：${diary.institutionName}")
    if (info.isNotEmpty()) {
        append(info.joinToString("\n")).append("\n\n")
    }

    // 术前图片
    val beforeUrls = diary.beforeImages.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (beforeUrls.isNotEmpty()) {
        append("📸 术前照片：\n")
        append(beforeUrls.joinToString("\n")).append("\n\n")
    }

    // 术后图片
    val afterUrls = diary.afterImages.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (afterUrls.isNotEmpty()) {
        append("📸 术后照片：\n")
        append(afterUrls.joinToString("\n")).append("\n\n")
    }

    // 标签
    if (diary.tags.isNotEmpty()) {
        append(diary.tags.joinToString(" ") { "#$it" })
    }
}
