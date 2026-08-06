package com.joysong.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.joysong.app.R
import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.ExpertArticle
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.ProjectWithInstitutions
import com.joysong.app.ui.components.DiaryCard
import com.joysong.app.ui.components.FavoriteButton
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.PlaceholderImage
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.PrimaryLight
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary


private val tabs = listOf("All", "Projects", "Doctors", "Institutions", "Diaries", "Articles")

@Composable
private fun tabLabel(key: String): String = when (key) {
    "All" -> stringResource(R.string.discover_all)
    "Projects" -> stringResource(R.string.tab_projects)
    "Doctors" -> stringResource(R.string.tab_doctors)
    "Institutions" -> stringResource(R.string.verified_institutions)
    "Diaries" -> stringResource(R.string.tab_diaries)
    "Articles" -> stringResource(R.string.tab_articles)
    else -> key
}

@Composable
fun DiscoverScreen(
    onProjectClick: (String) -> Unit,
    onInstitutionClick: (String) -> Unit,
    onDoctorClick: (String) -> Unit,
    onArticleClick: (String) -> Unit,
    onDiaryClick: (String) -> Unit,
    onFilterClick: () -> Unit,
    onInstitutionProjectClick: (String, String) -> Unit = { _, _ -> },
    initialTab: Int = 0,
    viewModel: DiscoverViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(initialTab) {
        // 仅在显式指定 initialTab > 0 时切换，避免从筛选页返回时重置为 All Tab
        if (initialTab > 0) {
            pagerState.scrollToPage(initialTab)
            viewModel.selectTab(initialTab)
        }
    }

    // 滑动 Pager 时同步 ViewModel 的 selectedTab
    LaunchedEffect(pagerState.currentPage) {
        viewModel.selectTab(pagerState.currentPage)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        SearchBox(
            query = uiState.searchQuery,
            onQueryChange = { viewModel.onSearchQueryChange(it) },
            onSearch = { viewModel.search() }
        )

        if (uiState.searchQuery.isBlank()) {
            SearchHistorySection(
                history = uiState.searchHistory,
                onItemClick = {
                    viewModel.onSearchQueryChange(it)
                    viewModel.search()
                },
                onItemRemove = { viewModel.removeHistoryItem(it) },
                onClearAll = { viewModel.clearHistory() }
            )
        }

        DiscoverTabs(
            pagerState = pagerState,
            coroutineScope = coroutineScope
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> AllTab(
                    uiState = uiState,
                    onProjectClick = onProjectClick,
                    onInstitutionClick = onInstitutionClick,
                    onDoctorClick = onDoctorClick,
                    onDiaryClick = onDiaryClick,
                    onArticleClick = onArticleClick,
                    onInstitutionProjectClick = onInstitutionProjectClick,
                    onToggleFavorite = { viewModel.toggleFavorite(it) }
                )
                1 -> ProjectTab(
                    uiState = uiState,
                    onFilterClick = onFilterClick,
                    onProjectClick = onProjectClick,
                    onInstitutionProjectClick = onInstitutionProjectClick
                )
                2 -> DoctorTab(
                    doctors = uiState.doctors,
                    isLoading = uiState.isLoading,
                    onDoctorClick = onDoctorClick
                )
                3 -> InstitutionTab(
                    institutions = uiState.institutions,
                    isLoading = uiState.isLoading,
                    onInstitutionClick = onInstitutionClick
                )
                4 -> DiaryTab(
                    diaries = uiState.diaries,
                    isLoading = uiState.isLoading,
                    onDiaryClick = onDiaryClick
                )
                5 -> ArticleTab(
                    articles = uiState.articles,
                    isLoading = uiState.isLoading,
                    onArticleClick = onArticleClick,
                    onAuthorClick = onDoctorClick,
                    onToggleFavorite = { id -> viewModel.toggleFavorite(id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(48.dp),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = TextPrimary),
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Search
        ),
        keyboardActions = KeyboardActions(
            onSearch = { onSearch() }
        ),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = query,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_placeholder),
                        color = TextHint,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TextHint,
                        modifier = Modifier.size(20.dp)
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceVariant,
                    unfocusedContainerColor = SurfaceVariant,
                    focusedBorderColor = PrimaryDark,
                    unfocusedBorderColor = SurfaceVariant
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                container = {
                    OutlinedTextFieldDefaults.ContainerBox(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceVariant,
                            unfocusedContainerColor = SurfaceVariant,
                            focusedBorderColor = PrimaryDark,
                            unfocusedBorderColor = SurfaceVariant
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            )
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistorySection(
    history: List<String>,
    onItemClick: (String) -> Unit,
    onItemRemove: (String) -> Unit,
    onClearAll: () -> Unit
) {
    if (history.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_history_title),
                fontSize = 13.sp,
                color = TextSecondary,
                fontWeight = FontWeight.Medium
            )
            IconButton(onClick = onClearAll, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.clear_search_history),
                    tint = TextHint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            history.forEach { item ->
                Row(
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceVariant)
                        .clickable { onItemClick(item) }
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = item, fontSize = 13.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onItemRemove(item) }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun DiscoverTabs(
    pagerState: androidx.compose.foundation.pager.PagerState,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val selectedIndex = pagerState.currentPage
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 16.dp,
        containerColor = Background,
        contentColor = TextPrimary,
        indicator = { tabPositions ->
            if (selectedIndex < tabPositions.size) {
                TabRowDefaults.Indicator(
                    modifier = Modifier
                        .tabIndicatorOffset(tabPositions[selectedIndex])
                        .padding(horizontal = 12.dp),
                    height = 2.dp,
                    color = TextPrimary
                )
            }
        },
        divider = {
            Divider(color = SurfaceVariant, thickness = 0.5.dp)
        }
    ) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = selectedIndex == index,
                onClick = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(index)
                    }
                },
                text = {
                    Text(
                        text = tabLabel(title),
                        fontSize = 14.sp,
                        fontWeight = if (selectedIndex == index) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedIndex == index) TextPrimary else TextSecondary
                    )
                }
            )
        }
    }
}

@Composable
private fun ProjectTab(
    uiState: DiscoverUiState,
    onFilterClick: () -> Unit,
    onProjectClick: (String) -> Unit,
    onInstitutionProjectClick: (String, String) -> Unit = { _, _ -> }
) {
    val hasActiveFilter = uiState.searchQuery.isNotBlank() ||
        uiState.selectedCategories.isNotEmpty() ||
        uiState.selectedTags.isNotEmpty() ||
        uiState.selectedCities.isNotEmpty()

    val activeFilterCount = (if (uiState.selectedCategories.isNotEmpty()) 1 else 0) +
        (if (uiState.selectedTags.isNotEmpty()) 1 else 0) +
        (if (uiState.selectedCities.isNotEmpty()) 1 else 0)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // Filter icon
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filterTint = if (activeFilterCount > 0) PrimaryDark else TextSecondary
                Row(
                    modifier = Modifier
                        .clickable { onFilterClick() }
                        .padding(end = 8.dp, top = 8.dp, bottom = 8.dp, start = 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(R.string.filter_title),
                            tint = filterTint,
                            modifier = Modifier.size(20.dp)
                        )
                        if (activeFilterCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 1.dp, end = 1.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(PrimaryDark)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.filter_title),
                        fontSize = 13.sp,
                        color = filterTint,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (uiState.isLoading) {
            item { LoadingIndicator() }
        } else if (uiState.projects.isEmpty()) {
            item { DiscoverEmptyState(message = stringResource(R.string.discover_empty_projects)) }
        } else {
            items(uiState.projects) { projectWithInst ->
                ProjectListItemWithInstitutions(
                    projectWithInstitutions = projectWithInst,
                    onClick = { onProjectClick(projectWithInst.project.id) },
                    autoExpand = hasActiveFilter,
                    onInstitutionProjectClick = onInstitutionProjectClick
                )
            }
        }
    }
}

@Composable
private fun ProjectListItemWithInstitutions(
    projectWithInstitutions: ProjectWithInstitutions,
    onClick: () -> Unit,
    autoExpand: Boolean = false,
    onInstitutionProjectClick: (String, String) -> Unit = { _, _ -> }
) {
    val project = projectWithInstitutions.project
    val institutions = projectWithInstitutions.institutionProjects
    var isExpanded by remember { mutableStateOf(false) }

    // 当搜索/筛选条件变化时，自动展开或折叠
    LaunchedEffect(autoExpand) {
        isExpanded = autoExpand
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (project.coverImage.isNotBlank()) {
                AsyncImage(
                    model = project.coverImage,
                    contentDescription = project.name,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                PlaceholderImage(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    text = project.category
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = project.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (project.tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        project.tags.take(2).forEach { tag ->
                            TagChip(text = tag)
                        }
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
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
                        text = "${project.rating}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.reviews_count, project.reviewCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextHint
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.currency_format, project.referencePrice.toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PrimaryDark,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 机构项目列表 - 可折叠/展开
        if (institutions.isNotEmpty()) {
            // 折叠状态：显示"查看N个机构项目"按钮
            if (!isExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.view_institution_projects, institutions.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = PrimaryDark,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = PrimaryDark
                    )
                }
            }

            // 展开状态：显示机构项目列表 + 收起按钮
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    institutions.forEach { instProject ->
                        InstitutionProjectRow(
                            instProject = instProject,
                            onClick = {
                                onInstitutionProjectClick(
                                    instProject.institutionId,
                                    instProject.projectId
                                )
                            }
                        )
                        if (instProject != institutions.last()) {
                            Divider(
                                color = SurfaceVariant,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                    // 收起按钮
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isExpanded = false }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.collapse_institution_projects),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstitutionProjectRow(
    instProject: com.joysong.app.domain.model.InstitutionProjectItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (instProject.coverImage.isNotBlank()) {
            AsyncImage(
                model = instProject.coverImage,
                contentDescription = instProject.institutionName,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            PlaceholderImage(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                text = instProject.institutionName.take(1)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = instProject.name.ifBlank { instProject.institutionName },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (instProject.institutionCity.isNotBlank()) {
                Text(
                    text = listOf(instProject.institutionName, instProject.institutionCity).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextHint,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = stringResource(R.string.currency_format, instProject.price.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = PrimaryDark,
                fontWeight = FontWeight.Bold
            )
            if (instProject.salesCount > 0) {
                Text(
                    text = stringResource(R.string.sales_count_format, instProject.salesCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextHint,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ProjectListItem(
    projectWithInstitutions: ProjectWithInstitutions,
    onClick: () -> Unit
) {
    // Delegate to the new component
    ProjectListItemWithInstitutions(
        projectWithInstitutions = projectWithInstitutions,
        onClick = onClick
    )
}

@Composable
private fun TagChip(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(SurfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun DoctorTab(
    doctors: List<Doctor>,
    isLoading: Boolean,
    onDoctorClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        if (isLoading) {
            item { LoadingIndicator() }
        } else if (doctors.isEmpty()) {
            item { DiscoverEmptyState(message = stringResource(R.string.discover_empty_doctors)) }
        } else {
            items(doctors) { doctor ->
                DoctorListItem(doctor = doctor, onClick = { onDoctorClick(doctor.id) })
            }
        }
    }
}

@Composable
private fun DoctorListItem(doctor: Doctor, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
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
                    .size(64.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            PlaceholderImage(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape),
                text = stringResource(R.string.doctor_placeholder)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = doctor.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                if (doctor.isVerified) {
                    Icon(
                        imageVector = Icons.Outlined.Verified,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(16.dp),
                        tint = PrimaryDark
                    )
                }
            }
            Text(
                text = "${doctor.title} · ${doctor.institutionName}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (doctor.specialties.isNotEmpty()) {
                Text(
                    text = doctor.specialties.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
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
                    text = "${doctor.rating}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.reviews_count, doctor.reviewCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextHint
                )
            }
        }
    }
}

@Composable
private fun InstitutionTab(
    institutions: List<Institution>,
    isLoading: Boolean,
    onInstitutionClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        if (isLoading) {
            item { LoadingIndicator() }
        } else if (institutions.isEmpty()) {
            item { DiscoverEmptyState(message = stringResource(R.string.discover_empty_institutions)) }
        } else {
            items(institutions) { institution ->
                InstitutionListItem(institution = institution, onClick = { onInstitutionClick(institution.id) })
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = institution.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (institution.isVerified) {
                    Icon(
                        imageVector = Icons.Outlined.Verified,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(16.dp),
                        tint = PrimaryDark
                    )
                }
            }
            Text(
                text = institution.address,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (institution.rating > 0f) {
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
}

@Composable
private fun DiaryTab(
    diaries: List<Diary>,
    isLoading: Boolean,
    onDiaryClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (isLoading) {
            item { LoadingIndicator() }
        } else if (diaries.isEmpty()) {
            item { DiscoverEmptyState(message = stringResource(R.string.discover_empty_diaries)) }
        } else {
            items(diaries) { diary ->
                DiaryCard(
                    diary = diary,
                    onClick = { onDiaryClick(diary.id) },
                    showBeforeAfter = true,
                    showProjectTag = true,
                    showStats = true
                )
            }
        }
    }
}

@Composable
private fun ArticleTab(
    articles: List<ExpertArticle>,
    isLoading: Boolean,
    onArticleClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit = {},
    onToggleFavorite: (String) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        if (isLoading) {
            item { LoadingIndicator() }
        } else if (articles.isEmpty()) {
            item { DiscoverEmptyState(message = stringResource(R.string.discover_empty_articles)) }
        } else {
            items(articles, key = { it.id }) { article ->
                ArticleListItem(
                    article = article,
                    onClick = { onArticleClick(article.id) },
                    onAuthorClick = { if (article.doctorId.isNotEmpty()) onAuthorClick(article.doctorId) },
                    onToggleFavorite = { onToggleFavorite(article.id) }
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
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
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
                            color = PrimaryDark,
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
private fun AllTab(
    uiState: DiscoverUiState,
    onProjectClick: (String) -> Unit,
    onInstitutionClick: (String) -> Unit,
    onDoctorClick: (String) -> Unit,
    onDiaryClick: (String) -> Unit,
    onArticleClick: (String) -> Unit,
    onInstitutionProjectClick: (String, String) -> Unit = { _, _ -> },
    onToggleFavorite: (String) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        if (uiState.isLoading) {
            item { LoadingIndicator() }
            return@LazyColumn
        }

        if (uiState.projects.isEmpty() && uiState.institutions.isEmpty() &&
            uiState.doctors.isEmpty() && uiState.diaries.isEmpty() && uiState.articles.isEmpty()
        ) {
            item { DiscoverEmptyState(message = stringResource(R.string.discover_empty_all)) }
            return@LazyColumn
        }

        if (uiState.projects.isNotEmpty()) {
            item {
                SectionTitle(title = stringResource(R.string.tab_projects))
            }
            items(uiState.projects.take(3)) { projectWithInst ->
                ProjectListItemWithInstitutions(
                    projectWithInstitutions = projectWithInst,
                    onClick = { onProjectClick(projectWithInst.project.id) },
                    autoExpand = false,
                    onInstitutionProjectClick = onInstitutionProjectClick
                )
            }
        }

        if (uiState.institutions.isNotEmpty()) {
            item {
                SectionTitle(title = stringResource(R.string.verified_institutions))
            }
            items(uiState.institutions.take(2)) { institution ->
                InstitutionListItem(institution = institution, onClick = { onInstitutionClick(institution.id) })
            }
        }

        if (uiState.doctors.isNotEmpty()) {
            item {
                SectionTitle(title = stringResource(R.string.recommended_doctors))
            }
            items(uiState.doctors.take(3)) { doctor ->
                DoctorListItem(doctor = doctor, onClick = { onDoctorClick(doctor.id) })
            }
        }

        if (uiState.diaries.isNotEmpty()) {
            item {
                SectionTitle(title = stringResource(R.string.tab_diaries))
            }
            items(uiState.diaries.take(2)) { diary ->
                DiaryCard(
                    diary = diary,
                    onClick = { onDiaryClick(diary.id) },
                    showBeforeAfter = true,
                    showProjectTag = true,
                    showStats = true
                )
            }
        }

        if (uiState.articles.isNotEmpty()) {
            item {
                SectionTitle(title = stringResource(R.string.tab_articles))
            }
            items(uiState.articles.take(3), key = { it.id }) { article ->
                ArticleListItem(
                    article = article,
                    onClick = { onArticleClick(article.id) },
                    onAuthorClick = { if (article.doctorId.isNotEmpty()) onDoctorClick(article.doctorId) },
                    onToggleFavorite = { onToggleFavorite(article.id) }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = TextPrimary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun DiscoverEmptyState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = TextHint,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.discover_empty_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
