package com.joysong.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.joysong.app.R
import com.joysong.app.domain.model.Favorite
import com.joysong.app.domain.model.FavoriteType
import com.joysong.app.ui.components.EmptyView
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.PlaceholderImage
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary

private enum class FavoriteTab(
    val type: FavoriteType?,
    @androidx.annotation.StringRes val titleRes: Int
) {
    All(null, R.string.fav_tab_all),
    Projects(FavoriteType.PROJECT, R.string.fav_projects),
    Institutions(FavoriteType.INSTITUTION, R.string.fav_institutions),
    Doctors(FavoriteType.DOCTOR, R.string.fav_doctors),
    Diaries(FavoriteType.DIARY, R.string.fav_diaries),
    Articles(FavoriteType.ARTICLE, R.string.fav_articles)
}

@Composable
fun FavoritesScreen(
    onBackClick: () -> Unit,
    onProjectClick: (String) -> Unit = {},
    onInstitutionClick: (String) -> Unit = {},
    onDoctorClick: (String) -> Unit = {},
    onDiaryClick: (String) -> Unit = {},
    onArticleClick: (String) -> Unit = {},
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = FavoriteTab.entries.toTypedArray()

    Scaffold(
        topBar = { JoysongTopBar(title = stringResource(R.string.my_favorites), onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Background)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Surface,
                contentColor = PrimaryDark,
                edgePadding = 0.dp,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = PrimaryDark
                    )
                }
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = stringResource(tab.titleRes),
                                fontSize = 14.sp,
                                fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            when {
                uiState.isLoading -> LoadingIndicator()
                uiState.error != null -> ErrorView(
                    message = uiState.error ?: stringResource(R.string.error_retry),
                    onRetry = { viewModel.loadFavorites() }
                )
                else -> {
                    val currentType = tabs[selectedTab].type
                    val items = if (currentType == null) {
                        uiState.favorites
                    } else {
                        uiState.favorites.filter { it.targetType == currentType }
                    }
                    if (items.isEmpty()) {
                        EmptyView(message = stringResource(R.string.favorites_empty))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(items, key = { it.id }) { favorite ->
                                FavoriteItem(
                                    favorite = favorite,
                                    onClick = {
                                        when (favorite.targetType) {
                                            FavoriteType.PROJECT -> onProjectClick(favorite.targetId)
                                            FavoriteType.INSTITUTION -> onInstitutionClick(favorite.targetId)
                                            FavoriteType.DOCTOR -> onDoctorClick(favorite.targetId)
                                            FavoriteType.DIARY -> onDiaryClick(favorite.targetId)
                                            FavoriteType.ARTICLE -> onArticleClick(favorite.targetId)
                                        }
                                    },
                                    onRemove = {
                                        viewModel.removeFavorite(favorite.targetType, favorite.targetId)
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

@Composable
private fun FavoriteItem(
    favorite: Favorite,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val placeholderText = when (favorite.targetType) {
        FavoriteType.PROJECT -> R.string.project_placeholder
        FavoriteType.INSTITUTION -> R.string.institution_placeholder
        FavoriteType.DOCTOR -> R.string.doctor_placeholder
        FavoriteType.DIARY -> R.string.diary_placeholder
        FavoriteType.ARTICLE -> R.string.article_placeholder
    }

    FavoriteRow(onClick = onClick) {
        val imageSize = if (favorite.targetType == FavoriteType.DOCTOR) 56.dp else 72.dp
        val imageShape = if (favorite.targetType == FavoriteType.DOCTOR) CircleShape else RoundedCornerShape(10.dp)
        if (favorite.targetImage.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(favorite.targetImage)
                    .crossfade(true)
                    .build(),
                contentDescription = favorite.targetName,
                modifier = Modifier
                    .size(imageSize)
                    .clip(imageShape),
                contentScale = ContentScale.Crop
            )
        } else {
            PlaceholderImage(
                modifier = Modifier
                    .size(imageSize)
                    .clip(imageShape),
                text = stringResource(placeholderText)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = favorite.targetName.ifBlank { stringResource(R.string.unknown) },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = favorite.createdAt,
                fontSize = 13.sp,
                color = TextHint,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.remove_favorite),
                tint = TextSecondary
            )
        }
    }
}

@Composable
private fun FavoriteRow(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}
