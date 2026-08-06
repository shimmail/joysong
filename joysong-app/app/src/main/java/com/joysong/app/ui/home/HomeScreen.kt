package com.joysong.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.joysong.app.R
import androidx.core.graphics.toColorInt
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.ExpertArticle
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.Project
import com.joysong.app.domain.model.RecommendedInstitutionProject
import com.joysong.app.ui.components.DiaryCard
import com.joysong.app.ui.components.DiaryCardStyle
import com.joysong.app.ui.components.FavoriteButton
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.PlaceholderImage
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.PrimaryLight
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onProjectClick: (String) -> Unit,
    onArticleClick: (String) -> Unit,
    onDiaryClick: (String) -> Unit,
    onInstitutionClick: (String) -> Unit,
    onDoctorClick: (String) -> Unit,
    onInstitutionProjectClick: (String, String) -> Unit = { _, _ -> },
    onViewAllClick: (Int) -> Unit = {},
    onAuthorClick: (String) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onNotificationClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val hasUnreadMessages by viewModel.hasUnreadMessages.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    if (uiState.isLoading && uiState.banners.isEmpty()) {
        LoadingIndicator()
        return
    }

    PullToRefreshBox(
        isRefreshing = uiState.isLoading,
        onRefresh = { viewModel.loadHomeData() },
        state = pullRefreshState,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
        item {
            HomeHeaderRow(
                nickname = uiState.nickname,
                hasUnreadMessages = hasUnreadMessages,
                onNotificationClick = onNotificationClick
            )
        }

        item {
            SearchBarRow(onSearchClick = onSearchClick)
        }

        item {
            if (uiState.error != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFF3E0))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.load_failed_retry),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE65100),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.retry),
                        style = MaterialTheme.typography.labelLarge,
                        color = Primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { viewModel.loadHomeData() }
                            .padding(start = 8.dp)
                    )
                }
            }
        }

        item {
            BannerCarousel(banners = uiState.banners)
        }

        item {
            AiRecommendSection(
                projects = uiState.recommendedInstitutionProjects.take(4),
                onProjectClick = { institutionId, projectId ->
                    onInstitutionProjectClick(institutionId, projectId)
                }
            )
        }

        item {
            SectionHeader(
                title = stringResource(R.string.hot_projects),
                action = stringResource(R.string.view_all),
                onActionClick = { onViewAllClick(1) }
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.hotProjects) { project ->
                    ProjectCard(project = project, onClick = { onProjectClick(project.id) })
                }
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.expert_articles),
                action = stringResource(R.string.view_all),
                onActionClick = { onViewAllClick(5) }
            )
        }
        items(uiState.expertArticles, key = { it.id }) { article ->
            ArticleListItem(
                article = article,
                onClick = { onArticleClick(article.id) },
                onAuthorClick = { if (article.doctorId.isNotEmpty()) onAuthorClick(article.doctorId) },
                onToggleFavorite = { viewModel.toggleFavorite(article.id) }
            )
        }

        item {
            SectionHeader(
                title = stringResource(R.string.featured_diaries),
                action = stringResource(R.string.view_all),
                onActionClick = { onViewAllClick(4) }
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.userDiaries) { diary ->
                    DiaryCard(
                        diary = diary,
                        style = DiaryCardStyle.VERTICAL,
                        onClick = { onDiaryClick(diary.id) },
                        showBeforeAfter = true,
                        showProjectTag = true,
                        showStats = true,
                        modifier = Modifier.width(320.dp)
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.verified_institutions),
                action = stringResource(R.string.view_all),
                onActionClick = { onViewAllClick(3) }
            )
        }
        items(uiState.institutions.take(3)) { institution ->
            InstitutionListItem(
                institution = institution,
                onClick = { onInstitutionClick(institution.id) }
            )
        }

        item {
            SectionHeader(
                title = stringResource(R.string.recommended_doctors),
                action = stringResource(R.string.view_all),
                onActionClick = { onViewAllClick(2) }
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                items(uiState.doctors.take(5)) { doctor ->
                    DoctorCard(
                        doctor = doctor,
                        onClick = { onDoctorClick(doctor.id) },
                        modifier = Modifier.fillParentMaxWidth(0.3f)
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun HomeHeaderRow(
    nickname: String,
    hasUnreadMessages: Boolean,
    onNotificationClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.hello_format, nickname.ifBlank { "..." }),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        IconButton(onClick = onNotificationClick) {
            Box {
                Icon(
                    imageVector = Icons.Outlined.Chat,
                    contentDescription = stringResource(R.string.messages_title),
                    tint = TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
                if (hasUnreadMessages) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE53935))
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBarRow(onSearchClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Surface)
            .clickable { onSearchClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = TextHint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.search_placeholder),
            color = TextHint,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String = "",
    onActionClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        if (action.isNotBlank()) {
            Text(
                text = action,
                fontSize = 13.sp,
                color = TextHint,
                modifier = Modifier.clickable(onClick = onActionClick)
            )
        }
    }
}

@Composable
private fun BannerCarousel(banners: List<com.joysong.app.domain.model.Banner>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(banners) { banner ->
            Card(
                modifier = Modifier
                    .width(300.dp)
                    .height(150.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(banner.accentColor.toColorInt())
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Column {
                        Text(
                            text = banner.title,
                            color = TextOnPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = banner.subtitle,
                            color = TextOnPrimary.copy(alpha = 0.9f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiRecommendSection(
    projects: List<RecommendedInstitutionProject>,
    onProjectClick: (String, String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = PrimaryDark,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.recommended_institution_projects),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(projects) { project ->
                AiProjectCard(project = project, onClick = { onProjectClick(project.institutionId, project.projectId) })
            }
        }
    }
}

@Composable
private fun ProjectCard(project: Project, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column {
            if (project.coverImage.isNotBlank()) {
                AsyncImage(
                    model = project.coverImage,
                    contentDescription = project.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                PlaceholderImage(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    text = project.category
                )
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = project.category,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = "¥${project.referencePrice.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PrimaryDark,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun AiProjectCard(project: RecommendedInstitutionProject, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(280.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (project.coverImage.isNotBlank()) {
                AsyncImage(
                    model = project.coverImage,
                    contentDescription = project.projectName,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                PlaceholderImage(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    text = project.category
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.institutionName,
                    fontSize = 11.sp,
                    color = TextHint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(PrimaryLight.copy(alpha = 0.3f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    maxLines = 1
                )
                Text(
                    text = project.projectName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = project.category,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = "¥${project.price.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PrimaryDark,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ArticleListItem(
    article: ExpertArticle,
    onClick: () -> Unit,
    onAuthorClick: (() -> Unit)? = null,
    onToggleFavorite: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column {
            // 封面图 - 宽高比 2:1
            if (article.coverImage.isNotBlank()) {
                AsyncImage(
                    model = article.coverImage,
                    contentDescription = article.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                PlaceholderImage(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f),
                    text = stringResource(R.string.article_placeholder)
                )
            }

            // 文字内容区域
            Column(modifier = Modifier.padding(12.dp)) {
                // 标题
                Text(
                    text = article.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 发布时间 + 阅读时长 + 收藏按钮
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${article.publishDate} 发布",
                        fontSize = 12.sp,
                        color = TextHint
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    if (article.readCount > 0) {
                        Text(
                            text = "阅读${article.readCount}次",
                            fontSize = 12.sp,
                            color = TextHint
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    FavoriteButton(
                        isFavorited = article.isFavorited,
                        onClick = onToggleFavorite,
                        size = 24.dp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 作者信息行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (article.doctorId.isNotEmpty() && onAuthorClick != null) {
                        Text(
                            text = article.authorName,
                            fontSize = 13.sp,
                            color = Primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onAuthorClick() }
                        )
                    } else {
                        Text(
                            text = article.authorName,
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}



@Composable
private fun InstitutionListItem(institution: Institution, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (institution.coverImage.isNotBlank()) {
            AsyncImage(
                model = institution.coverImage,
                contentDescription = institution.name,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            PlaceholderImage(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp)),
                text = stringResource(R.string.institution_placeholder)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = institution.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1
            )
            Text(
                text = institution.address,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = PrimaryDark
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "${institution.rating}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.reviews_count, institution.reviewCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextHint
                )
            }
        }
    }
}

@Composable
private fun DoctorCard(doctor: Doctor, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (doctor.avatar.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(doctor.avatar)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build(),
                    contentDescription = doctor.name,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                PlaceholderImage(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                    text = stringResource(R.string.doctor_placeholder)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = doctor.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1
            )
            Text(
                text = doctor.title,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = doctor.specialties.take(2).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = TextHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
