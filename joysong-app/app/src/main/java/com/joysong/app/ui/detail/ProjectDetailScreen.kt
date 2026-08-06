package com.joysong.app.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.model.FavoriteType
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.InstitutionProject
import com.joysong.app.domain.model.Project
import com.joysong.app.ui.components.DiaryCard
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.FavoriteButton
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.PlaceholderImage
import kotlinx.coroutines.launch

// Design spec colors (inline)
private val PPageBg = Color(0xFFF5F5F5)
private val PCardBg = Color.White
private val PTitleColor = Color(0xFF1A1A1A)
private val PBodyColor = Color(0xFF666666)
private val PHintColor = Color(0xFF999999)
private val PPriceColor = Color(0xFFE53935)
private val PRatingColor = Color(0xFFFF9800)
private val PTagBg = Color(0xFFF0F0F0)
private val PAccentDark = Color(0xFF1A1A1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    onBackClick: () -> Unit,
    onAiChatClick: (project: Project) -> Unit = {},
    onBookClick: (project: Project) -> Unit = {},
    onInstitutionClick: (String) -> Unit = {},
    onDiaryClick: (String) -> Unit = {},
    onNavigateToAllDiaries: (String) -> Unit = {},
    onNavigateToAllInstitutions: (String) -> Unit = {},
    onInstitutionProjectClick: (String, String) -> Unit = { _, _ -> },
    viewModel: DetailViewModel = hiltViewModel()
) {
    val projectState by viewModel.project.collectAsState()
    val institutions by viewModel.projectInstitutions.collectAsState()
    val diaries by viewModel.projectDiaries.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(projectId) {
        viewModel.loadProject(projectId)
        viewModel.checkGenericFavoriteStatus(FavoriteType.PROJECT, projectId)
    }

    val isFavorited by viewModel.genericFavorited

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.project_detail),
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            if (projectState is DetailUiState.Success) {
                val project = (projectState as DetailUiState.Success<Project>).data
                ProjectBottomBar(
                    onAiChatClick = { onAiChatClick(project) },
                    onBookClick = {
                        coroutineScope.launch {
                            lazyListState.animateScrollToItem(3)
                        }
                    },
                    isFavorited = isFavorited,
                    onFavoriteClick = {
                        viewModel.toggleGenericFavorite(
                            FavoriteType.PROJECT, projectId, project.name, project.coverImage
                        )
                    }
                )
            }
        }
    ) { innerPadding ->
        when (projectState) {
            is DetailUiState.Loading -> LoadingIndicator()
            is DetailUiState.Error -> ErrorView(
                message = (projectState as DetailUiState.Error).message,
                onRetry = { viewModel.loadProject(projectId) }
            )
            is DetailUiState.Success -> {
                val project = (projectState as DetailUiState.Success<Project>).data
                // Section indices: 0=Header, 1=NavBar, 2=Guide, 3=Institutions, 4=Diaries
                val sectionIndices = listOf(2, 3, 4)
                val navLabels = listOf(
                    stringResource(R.string.tab_project_guide),
                    stringResource(R.string.tab_certified_institutions),
                    stringResource(R.string.tab_user_diaries)
                )

                // Bottom sheet state
                var showInfoSheet by remember { mutableStateOf(false) }

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refreshProject(projectId) },
                    state = pullRefreshState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(PPageBg)
                ) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 0: Header
                        item {
                            ProjHeader(project)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 1: Anchor Navigation Bar
                        item {
                            ProjAnchorNavBar(
                                labels = navLabels,
                                onSectionClick = { index ->
                                    coroutineScope.launch {
                                        lazyListState.animateScrollToItem(sectionIndices.getOrNull(index) ?: index)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 2: Project Guide Section
                        item {
                            ProjGuideSection(
                                project = project,
                                onShowDetail = {
                                    showInfoSheet = true
                                }
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        // 3: Certified Institutions Section
                        item {
                            ProjInstitutionsSection(
                                institutionPairs = institutions,
                                onInstitutionClick = onInstitutionClick,
                                onInstitutionProjectClick = onInstitutionProjectClick,
                                onNavigateToAllInstitutions = { onNavigateToAllInstitutions(projectId) }
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        // 4: Diaries Section
                        item {
                            ProjDiariesSection(diaries, onDiaryClick, onNavigateToAllDiaries = { onNavigateToAllDiaries(projectId) })
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }

                // Bottom sheet for project details
                if (showInfoSheet) {
                    ProjectInfoBottomSheet(
                        project = project,
                        onDismiss = { showInfoSheet = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjAnchorNavBar(
    labels: List<String>,
    onSectionClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PCardBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEachIndexed { index, label ->
            Box(
                modifier = Modifier
                    .clickable { onSectionClick(index) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = PAccentDark,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ProjHeader(project: Project) {
    Column(modifier = Modifier.background(PCardBg)) {
        // Cover image / image gallery
        val allImages = remember(project.images, project.coverImage) {
            val imgs = project.images.filter { it.isNotBlank() }
            if (imgs.isNotEmpty()) imgs
            else if (project.coverImage.isNotBlank()) listOf(project.coverImage)
            else emptyList()
        }
        if (allImages.isNotEmpty()) {
            DynamicImagePager(
                images = allImages,
                contentDescription = project.name,
                backgroundColor = PCardBg
            )
        } else {
            PlaceholderImage(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                text = project.category
            )
        }

        Column(modifier = Modifier.padding(16.dp)) {
            // Project name
            Text(
                text = project.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = PTitleColor
            )
            // Slogan
            if (project.slogan.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = project.slogan,
                    fontSize = 14.sp,
                    color = PBodyColor,
                    lineHeight = 20.sp
                )
            }
            // Category tags (moved up after slogan)
            if (project.categoryTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    project.categoryTags.forEach { tag ->
                        Text(
                            text = tag,
                            fontSize = 12.sp,
                            color = PBodyColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(PTagBg)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            // Reference price + sales (moved down)
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                if (project.referencePrice > 0) {
                    Text(
                        text = stringResource(R.string.reference_price_label_format, project.referencePrice.toInt()),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = PPriceColor
                    )
                }
                if (project.salesCount > 0) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.sales_count_format, project.salesCount),
                        fontSize = 12.sp,
                        color = PHintColor,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
            // Rating row
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Star, contentDescription = null, tint = PRatingColor, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("${project.rating}", fontSize = 14.sp, color = PTitleColor, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.reviews_count, project.reviewCount), fontSize = 13.sp, color = PBodyColor)
            }
        }
    }
}

@Composable
private fun ProjGuideSection(project: Project, onShowDetail: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.tab_project_guide),
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = PTitleColor
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PCardBg)
                .padding(16.dp)
        ) {
            // Project description
            if (project.description.isNotBlank()) {
                Text(
                    text = project.description,
                    fontSize = 14.sp,
                    color = PBodyColor,
                    lineHeight = 22.sp
                )
            }
            // Show more button
            if (!project.detailContent.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onShowDetail() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.view_more_detail),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = PAccentDark
                    )
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = PAccentDark,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjDiariesSection(
    diaries: List<Diary>,
    onDiaryClick: (String) -> Unit,
    onNavigateToAllDiaries: () -> Unit = {}
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.tab_user_diaries),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = PTitleColor
            )
            if (diaries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.all_count, diaries.size),
                    fontSize = 13.sp,
                    color = PAccentDark,
                    modifier = Modifier.clickable { onNavigateToAllDiaries() }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (diaries.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_user_diaries), fontSize = 14.sp, color = PHintColor)
            }
            return
        }

        // Horizontal diary list
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            diaries.take(5).forEach { diary ->
                DiaryCard(
                    diary = diary,
                    onClick = { onDiaryClick(diary.id) },
                    showBeforeAfter = true,
                    showProjectTag = true,
                    showStats = true,
                    maxContentLines = 3,
                    modifier = Modifier.width(280.dp)
                )
            }
        }
    }
}

@Composable
private fun ProjInstitutionsSection(
    institutionPairs: List<Pair<InstitutionProject, Institution>>,
    onInstitutionClick: (String) -> Unit,
    onInstitutionProjectClick: (String, String) -> Unit = { _, _ -> },
    onNavigateToAllInstitutions: () -> Unit = {}
) {
    if (institutionPairs.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_certified_institutions), fontSize = 14.sp, color = PHintColor)
        }
        return
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.tab_certified_institutions),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = PTitleColor
            )
            Text(
                text = stringResource(R.string.all_count, institutionPairs.size),
                fontSize = 13.sp,
                color = PAccentDark,
                modifier = Modifier.clickable { onNavigateToAllInstitutions() }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        institutionPairs.take(5).forEach { (instProject, institution) ->
            ProjInstitutionCard(
                institution = institution,
                instProject = instProject,
                onInstitutionClick = { onInstitutionClick(institution.id) },
                onProjectClick = { onInstitutionProjectClick(institution.id, instProject.projectId) }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ProjInstitutionCard(
    institution: Institution,
    instProject: InstitutionProject,
    onInstitutionClick: () -> Unit,
    onProjectClick: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PCardBg)
    ) {
        // Institution info row (clickable to navigate)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onInstitutionClick() }
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Institution logo
            if (institution.coverImage.isNotBlank()) {
                AsyncImage(
                    model = institution.coverImage,
                    contentDescription = institution.name,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                    onError = { /* fallback */ }
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                // Name + verified
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (instProject.name.isNotBlank()) {
                        Text(instProject.name, fontSize = 13.sp, color = PTitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        institution.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = PTitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (institution.isVerified) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Filled.Verified, contentDescription = null, tint = PAccentDark, modifier = Modifier.size(14.dp))
                    }
                }
                // Address
                if (institution.address.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = PHintColor, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(institution.address, fontSize = 12.sp, color = PBodyColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Rating
                if (institution.rating > 0f) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Star, contentDescription = null, tint = PRatingColor, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("%.1f (%d)".format(institution.rating, institution.reviewCount), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PTitleColor)
                    }
                }
            }
        }
        // Expand/collapse toggle for project info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isExpanded) stringResource(R.string.collapse_text) else stringResource(R.string.view_institution_projects, 1),
                fontSize = 12.sp,
                color = PAccentDark,
                fontWeight = FontWeight.Medium
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = PAccentDark
            )
        }
        // Expanded: project price info
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onProjectClick() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.inst_project_price_format, instProject.price),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = PPriceColor
                    )
                    if (instProject.originalPrice != null && instProject.originalPrice > instProject.price) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.inst_project_original_price_format, instProject.originalPrice),
                            fontSize = 12.sp,
                            color = PHintColor,
                            textDecoration = TextDecoration.LineThrough
                        )
                    }
                    if (instProject.salesCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sales_count_format, instProject.salesCount),
                            fontSize = 11.sp,
                            color = PHintColor
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.book_project),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = PAccentDark
                    )
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PAccentDark, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun ProjectBottomBar(
    onAiChatClick: () -> Unit,
    onBookClick: () -> Unit,
    isFavorited: Boolean = false,
    onFavoriteClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PCardBg)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Favorite button
        FavoriteButton(
            isFavorited = isFavorited,
            onClick = onFavoriteClick,
            size = 28.dp
        )
        // "Chat with AI" button
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(PCardBg)
                .border(1.dp, PAccentDark, RoundedCornerShape(22.dp))
                .clickable { onAiChatClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.chat_with_ai_about_project), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PTitleColor, maxLines = 1, textAlign = TextAlign.Center)
        }
        // "View Bookable Institutions" button
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(PAccentDark)
                .clickable { onBookClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.view_bookable_institutions), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

