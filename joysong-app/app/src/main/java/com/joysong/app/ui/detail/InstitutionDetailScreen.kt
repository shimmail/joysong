package com.joysong.app.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.FavoriteType
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.InstitutionProjectInfo
import com.joysong.app.domain.model.Review
import com.joysong.app.ui.components.DiaryCard
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.ImageZoomDialog
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.PlaceholderImage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.joysong.app.ui.components.FavoriteButton
import com.joysong.app.ui.components.ReportDialog
import com.joysong.app.ui.components.TagFilterBar
import com.joysong.app.ui.report.ReportViewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn

// Design spec colors (inline)
private val PageBg = Color(0xFFF5F5F5)
private val CardBg = Color.White
private val TitleColor = Color(0xFF1A1A1A)
private val BodyColor = Color(0xFF666666)
private val HintColor = Color(0xFF999999)
private val PriceColor = Color(0xFFE53935)
private val RatingColor = Color(0xFFFF9800)
private val TagBg = Color(0xFFF0F0F0)
private val AccentDark = Color(0xFF1A1A1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstitutionDetailScreen(
    institutionId: String,
    onBackClick: () -> Unit,
    onAiChatClick: (institution: Institution) -> Unit = {},
    onConsultClick: (institution: Institution) -> Unit = {},
    onProjectClick: (String) -> Unit = {},
    onDiaryClick: (String) -> Unit = {},
    onDoctorClick: (String) -> Unit = {},
    onNavigateToAllDiaries: (String) -> Unit = {},
    onNavigateToAllProjects: (String) -> Unit = {},
    onNavigateToAllDoctors: (String) -> Unit = {},
    onNavigateToAllReviews: (String) -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel(),
    reportViewModel: ReportViewModel = hiltViewModel()
) {
    val institutionState by viewModel.institution.collectAsState()
    val projects by viewModel.institutionProjects.collectAsState()
    val doctors by viewModel.institutionDoctors.collectAsState()
    val diaries by viewModel.institutionDiaries.collectAsState()
    val reviews by viewModel.institutionReviews.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    LaunchedEffect(institutionId) {
        viewModel.loadInstitution(institutionId)
        viewModel.checkGenericFavoriteStatus(FavoriteType.INSTITUTION, institutionId)
    }

    // Check favorite status for each project when projects are loaded
    LaunchedEffect(projects) {
        projects.forEach { project ->
            viewModel.checkProjectFavorite(project.projectId)
        }
    }

    val isFavorited by viewModel.genericFavorited
    val isFavoriteLoading by viewModel.genericFavoriteLoading
    val projectFavoriteStates by viewModel.projectFavoriteStates

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.institution_detail),
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            if (institutionState is DetailUiState.Success) {
                val institution = (institutionState as DetailUiState.Success<Institution>).data
                InstitutionBottomBar(
                    onAiChatClick = { onAiChatClick(institution) },
                    onConsultClick = { onConsultClick(institution) },
                    isFavorited = isFavorited,
                    onFavoriteClick = {
                        viewModel.toggleGenericFavorite(
                            FavoriteType.INSTITUTION, institutionId, institution.name, institution.coverImage
                        )
                    }
                )
            }
        }
    ) { innerPadding ->
        when (institutionState) {
            is DetailUiState.Loading -> LoadingIndicator()
            is DetailUiState.Error -> ErrorView(
                message = (institutionState as DetailUiState.Error).message,
                onRetry = { viewModel.loadInstitution(institutionId) }
            )
            is DetailUiState.Success -> {
                val institution = (institutionState as DetailUiState.Success<Institution>).data
                val lazyListState = rememberLazyListState()
                val coroutineScope = rememberCoroutineScope()
                val pullRefreshState = rememberPullToRefreshState()
                val sectionIndices = remember { listOf(3, 4, 5, 6, 7) } // Anchor sections indices in LazyColumn (5 nav buttons)

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refreshInstitution(institutionId) },
                    state = pullRefreshState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(PageBg)
                ) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 0: Header
                        item {
                            InstHeader(institution)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 1: Feature Cards
                        item {
                            InstFeatureCards()
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 2: Anchor Navigation Bar
                        item {
                            AnchorNavBar(
                                hasDoctors = doctors.isNotEmpty(),
                                onSectionClick = { index ->
                                    coroutineScope.launch {
                                        lazyListState.animateScrollToItem(sectionIndices.getOrNull(index) ?: index)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 3: Qualification Section
                        item {
                            InstQualificationSection(institution)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 4: Projects Section
                        item {
                            InstProjectsSection(
                                projects = projects,
                                onProjectClick = onProjectClick,
                                onNavigateToAllProjects = { onNavigateToAllProjects(institutionId) },
                                projectFavoriteStates = projectFavoriteStates,
                                onProjectFavoriteClick = { project ->
                                    viewModel.toggleProjectFavorite(
                                        project.projectId, project.projectName, project.coverImage
                                    )
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 5: Diaries Section
                        item {
                            InstDiariesSection(diaries, onDiaryClick, onNavigateToAllDiaries = { onNavigateToAllDiaries(institutionId) })
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 6: Reviews Section
                        item {
                            InstReviewsSection(reviews, onNavigateToAllReviews = { onNavigateToAllReviews(institutionId) }, onReport = { reviewId ->
                                reportViewModel.showReport("review", reviewId)
                            })
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 7: Doctor Team Section
                        if (doctors.isNotEmpty()) {
                            item {
                                InstDoctorTeamSection(doctors, onDoctorClick, onNavigateToAllDoctors = { onNavigateToAllDoctors(institutionId) })
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // 举报对话框
    ReportDialog(
        reportViewModel = reportViewModel,
        onDismiss = { reportViewModel.hideReport() }
    )
}

@Composable
private fun InstHeader(inst: Institution) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg)
    ) {
        // Cover image / image carousel with HorizontalPager
        val coverImages = remember(inst.images, inst.coverImage) {
            val imgs = inst.images.filter { it.isNotBlank() }
            if (imgs.isNotEmpty()) imgs
            else if (inst.coverImage.isNotBlank()) listOf(inst.coverImage)
            else emptyList()
        }
        if (coverImages.isNotEmpty()) {
            DynamicImagePager(
                images = coverImages,
                contentDescription = inst.name,
                backgroundColor = CardBg
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val context = LocalContext.current
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            // Name centered
            Text(
                text = inst.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TitleColor
            )
            Spacer(modifier = Modifier.height(6.dp))
            // Address / distance — 点击复制地址
            val addressText = if (inst.city.isNotBlank()) stringResource(R.string.city_address_format, inst.city, inst.address) else inst.address
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    clipboard.setPrimaryClip(ClipData.newPlainText("地址", addressText))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = null,
                    tint = HintColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (inst.city.isNotBlank()) stringResource(R.string.city_address_format, inst.city, inst.address) else inst.address,
                    fontSize = 12.sp,
                    color = HintColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            // Contact phone + business hours（上移到地址下方、标签上方）
            if (inst.contactPhone.isNotBlank() || inst.businessHours.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (inst.contactPhone.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                clipboard.setPrimaryClip(ClipData.newPlainText("电话", inst.contactPhone))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Phone,
                                contentDescription = null,
                                tint = HintColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = inst.contactPhone,
                                fontSize = 12.sp,
                                color = BodyColor
                            )
                        }
                    }
                    if (inst.contactPhone.isNotBlank() && inst.businessHours.isNotBlank()) {
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    if (inst.businessHours.isNotBlank()) {
                        Icon(
                            imageVector = Icons.Outlined.AccessTime,
                            contentDescription = null,
                            tint = HintColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = inst.businessHours,
                            fontSize = 12.sp,
                            color = BodyColor
                        )
                    }
                }
            }
            // Tags row
            val tagList = remember(inst.specialties, inst.tags) {
                val s = if (inst.specialties.isBlank()) emptyList() else inst.specialties.split(",").filter { it.isNotBlank() }
                val t = if (inst.tags.isBlank()) emptyList() else inst.tags.split(",").filter { it.isNotBlank() }
                (s + t).distinct()
            }
            if (tagList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tagList.forEach { tag ->
                        Text(
                            text = tag,
                            fontSize = 12.sp,
                            color = BodyColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(TagBg)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            // Institution description
            if (inst.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = inst.description,
                    fontSize = 14.sp,
                    color = BodyColor,
                    lineHeight = 20.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Rating + caseCount
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    tint = RatingColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "%.1f (%d)".format(inst.rating, inst.reviewCount),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TitleColor
                )
                if (inst.caseCount > 0) {
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.case_count_plus_format, inst.caseCount),
                        fontSize = 13.sp,
                        color = BodyColor
                    )
                }
            }
        }
    }
}

@Composable
private fun InstFeatureCards() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Professional Doctors
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.School, contentDescription = null, tint = AccentDark, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.professional_doctors), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TitleColor)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.rich_experience), fontSize = 12.sp, color = BodyColor)
        }
        // Advanced Equipment
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Science, contentDescription = null, tint = AccentDark, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.advanced_equipment), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TitleColor)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.international_standards), fontSize = 12.sp, color = BodyColor)
        }
    }
}

@Composable
private fun AnchorNavBar(
    hasDoctors: Boolean,
    onSectionClick: (Int) -> Unit
) {
    val navItems = buildList {
        add(stringResource(R.string.tab_qualification))
        add(stringResource(R.string.tab_bookable_projects))
        add(stringResource(R.string.tab_user_diaries))
        add(stringResource(R.string.tab_user_reviews))
        if (hasDoctors) add(stringResource(R.string.tab_doctors))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        navItems.forEachIndexed { index, title ->
            Box(
                modifier = Modifier
                    .clickable { onSectionClick(index) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = AccentDark,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun InstQualificationSection(inst: Institution) {
    var showImageViewer by remember { mutableStateOf(false) }
    var viewerImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerInitialIndex by remember { mutableStateOf(0) }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        // Section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.institution_strength), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TitleColor)
            Text(stringResource(R.string.check_qualification), fontSize = 13.sp, color = AccentDark)
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Milestone list
        if (inst.establishedYear != null) {
            MilestoneItem(stringResource(R.string.established_year_format, inst.establishedYear))
        }
        if (inst.certificationTime.isNotBlank()) {
            MilestoneItem(stringResource(R.string.certification_time_format, inst.certificationTime))
        }
        if (inst.userCount > 0) {
            MilestoneItem(stringResource(R.string.user_count_format, inst.userCount))
        }
        // Credentials text
        if (inst.credentials.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            inst.credentials.split("\n").filter { it.isNotBlank() }.forEach { p ->
                Text(p, fontSize = 14.sp, color = BodyColor, lineHeight = 22.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        // Credential images - horizontal scroll, click to view full size
        val imageList = remember(inst.credentialImages) {
            if (inst.credentialImages.isBlank()) emptyList() else inst.credentialImages.split(",").filter { it.isNotBlank() }
        }
        if (imageList.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                imageList.forEachIndexed { index, url ->
                    AsyncImage(
                        model = url, contentDescription = null,
                        modifier = Modifier
                            .width(120.dp)
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                viewerImages = imageList
                                viewerInitialIndex = index
                                showImageViewer = true
                            },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }

    if (showImageViewer) {
        ImageZoomDialog(
            imageUrls = viewerImages,
            initialIndex = viewerInitialIndex,
            onDismiss = { showImageViewer = false }
        )
    }
}

@Composable
private fun MilestoneItem(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = BodyColor,
        lineHeight = 22.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun InstProjectsSection(
    projects: List<InstitutionProjectInfo>,
    onProjectClick: (String) -> Unit,
    onNavigateToAllProjects: () -> Unit = {},
    projectFavoriteStates: Map<String, Boolean> = emptyMap(),
    onProjectFavoriteClick: (InstitutionProjectInfo) -> Unit = {}
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        // Section title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.tab_bookable_projects),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TitleColor
            )
            if (projects.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.all_count, projects.size),
                    fontSize = 13.sp,
                    color = AccentDark,
                    modifier = Modifier.clickable { onNavigateToAllProjects() }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (projects.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.institution_projects_empty), fontSize = 14.sp, color = HintColor)
            }
            return
        }

        // Extract all unique tags from projects (category + categoryTags)
        val allTags = remember(projects) {
            val tags = mutableSetOf<String>()
            projects.forEach { p ->
                if (p.category.isNotBlank()) tags.add(p.category)
                p.categoryTags.filter { it.isNotBlank() }.forEach { tags.add(it) }
            }
            tags.toList()
        }

        var selectedTag by remember { mutableStateOf<String?>(null) }

        // Filter projects by selected tag
        val filteredProjects = remember(projects, selectedTag) {
            if (selectedTag == null) projects
            else projects.filter { p ->
                p.category == selectedTag || p.categoryTags.contains(selectedTag)
            }
        }

        if (allTags.isNotEmpty()) {
            TagFilterBar(
                tags = allTags,
                selectedTag = selectedTag,
                onTagSelected = { selectedTag = it }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (filteredProjects.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_filter_results), fontSize = 14.sp, color = HintColor)
            }
        } else {
            filteredProjects.take(5).forEach { project ->
                InstProjectCard(
                    project = project,
                    onClick = { onProjectClick(project.projectId) },
                    isFavorited = projectFavoriteStates[project.projectId] == true,
                    onFavoriteClick = { onProjectFavoriteClick(project) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun InstProjectCard(
    project: InstitutionProjectInfo,
    onClick: () -> Unit,
    isFavorited: Boolean = false,
    onFavoriteClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (project.coverImage.isNotBlank()) {
            AsyncImage(
                model = project.coverImage, contentDescription = project.projectName,
                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                onError = { /* fallback */ }
            )
        } else {
            PlaceholderImage(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)), text = stringResource(R.string.project_placeholder))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(project.projectName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TitleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (project.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(project.description.take(40) + if (project.description.length > 40) "…" else "", fontSize = 12.sp, color = BodyColor, maxLines = 1)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 机构特定价格（非参考价）
                Text(
                    text = stringResource(R.string.inst_project_price_format, project.price),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = PriceColor
                )
                // 原价（如有且不同于现价，显示划线价）
                if (project.originalPrice != null && project.originalPrice > project.price) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.inst_project_original_price_format, project.originalPrice),
                        fontSize = 12.sp,
                        color = HintColor,
                        textDecoration = TextDecoration.LineThrough
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    tint = RatingColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "%.1f".format(project.rating),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TitleColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.reviews_count, project.reviewCount),
                    fontSize = 12.sp,
                    color = HintColor
                )
            }
        }
        FavoriteButton(
            isFavorited = isFavorited,
            onClick = onFavoriteClick,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun InstDiariesSection(
    diaries: List<Diary>,
    onDiaryClick: (String) -> Unit,
    onNavigateToAllDiaries: () -> Unit = {}
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.tab_user_diaries), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TitleColor)
            if (diaries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.all_count, diaries.size),
                    fontSize = 13.sp,
                    color = AccentDark,
                    modifier = Modifier.clickable { onNavigateToAllDiaries() }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (diaries.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_user_diaries), fontSize = 14.sp, color = HintColor)
            }
            return
        }

        // Horizontal scrollable diaries
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
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
private fun InstReviewsSection(
    reviews: List<Review>,
    onNavigateToAllReviews: () -> Unit = {},
    onReport: (String) -> Unit = {}
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        // Section title with "All" button (diary-style)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.tab_user_reviews), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TitleColor)
            if (reviews.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.all_count, reviews.size),
                    fontSize = 13.sp,
                    color = AccentDark,
                    modifier = Modifier.clickable { onNavigateToAllReviews() }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (reviews.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.institution_reviews_empty), fontSize = 14.sp, color = HintColor)
            }
            return
        }

        // Show latest 5 reviews
        reviews.take(5).forEach { review ->
            InstReviewCard(review, onReport = { onReport(review.id) })
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun InstReviewCard(review: Review, onReport: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(12.dp)
    ) {
        // Stars
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(5) { index ->
                Icon(Icons.Outlined.Star, contentDescription = null, tint = if (index < review.rating) RatingColor else HintColor, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(review.content, fontSize = 14.sp, color = BodyColor, lineHeight = 22.sp)
        // Review tags
        if (review.tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                review.tags.forEach { tag ->
                    Text(
                        text = tag,
                        fontSize = 11.sp,
                        color = AccentDark,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(TagBg)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        // Images
        if (review.images.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                review.images.take(3).forEach { url ->
                    AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop, onError = { /* fallback */ })
                }
            }
        }
        // Author + date + report
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(review.userName.ifBlank { "\u533f\u540d\u7528\u6237" }, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TitleColor)
            Spacer(modifier = Modifier.width(8.dp))
            Text(review.createdAt, fontSize = 12.sp, color = HintColor)
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onReport, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = stringResource(R.string.report),
                    tint = HintColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun InstDoctorTeamSection(
    doctors: List<Doctor>,
    onDoctorClick: (String) -> Unit,
    onNavigateToAllDoctors: () -> Unit = {}
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.tab_core_doctors), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TitleColor)
            Text(
                text = stringResource(R.string.all_count, doctors.size),
                fontSize = 13.sp,
                color = AccentDark,
                modifier = Modifier.clickable { onNavigateToAllDoctors() }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        doctors.take(5).forEach { doctor ->
            InstDoctorCard(doctor, onClick = { onDoctorClick(doctor.id) })
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun InstDoctorCard(doctor: Doctor, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (doctor.avatar.isNotBlank()) {
                AsyncImage(model = doctor.avatar, contentDescription = doctor.name, modifier = Modifier.size(60.dp).clip(CircleShape), contentScale = ContentScale.Crop, onError = { /* fallback */ })
            } else {
                PlaceholderImage(modifier = Modifier.size(60.dp).clip(CircleShape), text = doctor.name.take(1))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("${doctor.name} ${doctor.title}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TitleColor)
                // Certification tags
                if (doctor.certificationTags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Verified, contentDescription = null, tint = AccentDark, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(doctor.certificationTags.first(), fontSize = 12.sp, color = AccentDark)
                    }
                } else if (doctor.isVerified) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Verified, contentDescription = null, tint = AccentDark, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.an_yan_certified), fontSize = 12.sp, color = AccentDark)
                    }
                }
                // Specialties
                if (doctor.specialties.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        doctor.specialties.forEach { s ->
                            Text(s, fontSize = 11.sp, color = BodyColor, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(TagBg).padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
                // Stats
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.doctor_review_consult, doctor.reviewCount, doctor.consultationCount),
                    fontSize = 12.sp, color = BodyColor
                )
            }
        }
    }
}

@Composable
private fun InstitutionBottomBar(
    onAiChatClick: () -> Unit,
    onConsultClick: () -> Unit,
    isFavorited: Boolean = false,
    onFavoriteClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg)
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
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(CardBg)
                .border(1.dp, AccentDark, RoundedCornerShape(22.dp))
                .clickable { onAiChatClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.chat_with_ai), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TitleColor, maxLines = 1, textAlign = TextAlign.Center)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(AccentDark)
                .clickable { onConsultClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.consult_institution), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

