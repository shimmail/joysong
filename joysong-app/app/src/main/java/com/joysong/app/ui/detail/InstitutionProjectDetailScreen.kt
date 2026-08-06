package com.joysong.app.ui.detail

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.FavoriteType
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.InstitutionProject
import com.joysong.app.domain.model.Project
import com.joysong.app.domain.repository.InstitutionProjectDetailInfo
import com.joysong.app.ui.components.DiaryCard
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.FavoriteButton
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.PlaceholderImage
import kotlinx.coroutines.launch

// Design spec colors
private val IPPageBg = Color(0xFFF5F5F5)
private val IPCardBg = Color.White
private val IPTitleColor = Color(0xFF1A1A1A)
private val IPBodyColor = Color(0xFF666666)
private val IPHintColor = Color(0xFF999999)
private val IPPriceColor = Color(0xFFE53935)
private val IPRatingColor = Color(0xFFFF9800)
private val IPTagBg = Color(0xFFF0F0F0)
private val IPAccentDark = Color(0xFF1A1A1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstitutionProjectDetailScreen(
    institutionId: String,
    projectId: String,
    onBackClick: () -> Unit,
    onAiChatClick: (institutionProjectId: String, projectName: String) -> Unit = { _, _ -> },
    onInstitutionClick: (String) -> Unit = {},
    onDiaryClick: (String) -> Unit = {},
    onNavigateToAllDiaries: (projectId: String) -> Unit = {},
    onNavigateToAllInstitutions: (projectId: String) -> Unit = {},
    onDoctorClick: (String) -> Unit = {},
    onNavigateToAllDoctors: (institutionId: String) -> Unit = {},
    onBookProjectClick: (institutionProjectId: String, projectId: String, institutionId: String) -> Unit = { _, _, _ -> },
    viewModel: DetailViewModel = hiltViewModel()
) {
    val detailState by viewModel.institutionProjectDetail.collectAsState()
    val ipDoctors by viewModel.ipDoctors.collectAsState()

    LaunchedEffect(institutionId, projectId) {
        viewModel.loadInstitutionProjectDetail(institutionId, projectId)
        viewModel.checkGenericFavoriteStatus(FavoriteType.PROJECT, projectId)
    }

    val isFavorited by viewModel.genericFavorited

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.institution_project_detail),
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            if (detailState is DetailUiState.Success) {
                val detail = (detailState as DetailUiState.Success<InstitutionProjectDetailInfo>).data
                InstitutionProjectBottomBar(
                    onAiChatClick = {
                        onAiChatClick(detail.institutionProject.id, detail.project.name)
                    },
                    onConsultClick = { onInstitutionClick(detail.institution.id) },
                    onBookClick = { onBookProjectClick(detail.institutionProject.id, projectId, institutionId) },
                    isFavorited = isFavorited,
                    onFavoriteClick = {
                        viewModel.toggleGenericFavorite(
                            FavoriteType.PROJECT, projectId, detail.project.name, detail.project.coverImage
                        )
                    }
                )
            }
        }
    ) { innerPadding ->
        when (detailState) {
            is DetailUiState.Loading -> LoadingIndicator()
            is DetailUiState.Error -> ErrorView(
                message = (detailState as DetailUiState.Error).message,
                onRetry = { viewModel.loadInstitutionProjectDetail(institutionId, projectId) }
            )
            is DetailUiState.Success -> {
                val detail = (detailState as DetailUiState.Success<InstitutionProjectDetailInfo>).data

                // Bottom sheet state
                var showInfoSheet by remember { mutableStateOf(false) }

                val navLabels = listOf(
                    stringResource(R.string.tab_project_guide),
                    stringResource(R.string.tab_bookable_doctors),
                    stringResource(R.string.tab_user_diaries),
                    stringResource(R.string.tab_affiliated_institutions)
                )

                val scrollState = rememberScrollState()
                val coroutineScope = rememberCoroutineScope()
                var section0Y by remember { mutableStateOf(0) }
                var section1Y by remember { mutableStateOf(0) }
                var section2Y by remember { mutableStateOf(0) }
                var section3Y by remember { mutableStateOf(0) }
                val sectionOffsets = listOf(section0Y, section1Y, section2Y, section3Y)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(scrollState)
                        .background(IPPageBg)
                ) {
                    // Header
                    IPHeader(
                        project = detail.project,
                        institutionProject = detail.institutionProject,
                        institution = detail.institution
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Anchor Nav (3 tabs)
                    IPAnchorNavBar(
                        labels = navLabels,
                        onSectionClick = { index ->
                            coroutineScope.launch {
                                scrollState.animateScrollTo(sectionOffsets.getOrElse(index) { 0 })
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Encyclopedia section (Tab 0)
                    IPEncyclopediaSection(
                        project = detail.project,
                        onShowDetail = { showInfoSheet = true },
                        modifier = Modifier.onGloballyPositioned { coords ->
                            section0Y = coords.positionInParent().y.toInt()
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Bookable doctors section (Tab 1)
                    IPDoctorsSection(
                        doctors = ipDoctors,
                        onDoctorClick = onDoctorClick,
                        onNavigateToAllDoctors = { onNavigateToAllDoctors(institutionId) },
                        modifier = Modifier.onGloballyPositioned { coords ->
                            section1Y = coords.positionInParent().y.toInt()
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Diaries section (Tab 2)
                    IPDiariesSection(
                        diaries = detail.diaries,
                        onDiaryClick = onDiaryClick,
                        onViewAllDiaries = { onNavigateToAllDiaries(projectId) },
                        modifier = Modifier.onGloballyPositioned { coords ->
                            section2Y = coords.positionInParent().y.toInt()
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Institution section (Tab 3)
                    IPInstitutionSection(
                        institutions = listOf(detail.institution),
                        onInstitutionClick = onInstitutionClick,
                        onViewAllInstitutions = { onNavigateToAllInstitutions(projectId) },
                        modifier = Modifier.onGloballyPositioned { coords ->
                            section3Y = coords.positionInParent().y.toInt()
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Bottom sheet for project details
                if (showInfoSheet) {
                    ProjectInfoBottomSheet(
                        project = detail.project,
                        onDismiss = { showInfoSheet = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun IPHeader(
    project: Project,
    institutionProject: InstitutionProject,
    institution: Institution
) {
    Column(modifier = Modifier.background(IPCardBg)) {
        // Image gallery with HorizontalPager
        val images = remember(institutionProject.images, project.images, institutionProject.coverImage, project.coverImage) {
            val list = institutionProject.images.ifEmpty { project.images }
            if (list.isEmpty()) {
                val fallback = institutionProject.coverImage.ifBlank { project.coverImage }
                if (fallback.isNotBlank()) listOf(fallback) else emptyList()
            } else {
                list
            }
        }

        if (images.isNotEmpty()) {
            DynamicImagePager(
                images = images,
                contentDescription = project.name,
                backgroundColor = IPCardBg
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
                color = IPTitleColor
            )
            // Slogan (moved up after name)
            if (project.slogan.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = project.slogan,
                    fontSize = 14.sp,
                    color = IPBodyColor,
                    lineHeight = 20.sp
                )
            }
            // Category tags (moved up from EncyclopediaSection)
            if (project.categoryTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    project.categoryTags.forEach { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(IPTagBg)
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = tag,
                                fontSize = 12.sp,
                                color = IPBodyColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            // Institution info row (moved down)
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Business, contentDescription = null, tint = IPHintColor, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = institution.name, fontSize = 14.sp, color = IPBodyColor)
            }
            // Institution price (moved down)
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "¥${institutionProject.price.toInt()}",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = IPPriceColor
                )
                institutionProject.originalPrice?.let { op ->
                    if (op > institutionProject.price) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "¥${op.toInt()}",
                            fontSize = 14.sp,
                            color = IPHintColor,
                            textDecoration = TextDecoration.LineThrough,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                }
                if (institutionProject.salesCount > 0) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.sales_count_format, institutionProject.salesCount),
                        fontSize = 12.sp,
                        color = IPHintColor,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
            // Institution-project rating and review count
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    tint = IPRatingColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "%.1f".format(institutionProject.rating),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = IPTitleColor
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.reviews_count, institutionProject.reviewCount),
                    fontSize = 13.sp,
                    color = IPBodyColor
                )
            }
            // Reference price (moved down)
            if (project.referencePrice > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.reference_price_label_format, project.referencePrice.toInt()),
                    fontSize = 12.sp,
                    color = IPHintColor
                )
            }
        }
    }
}

@Composable
private fun IPAnchorNavBar(
    labels: List<String>,
    onSectionClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(IPCardBg)
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
                    color = IPAccentDark,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun IPEncyclopediaSection(project: Project, onShowDetail: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.tab_project_guide),
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = IPTitleColor
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IPCardBg)
                .padding(16.dp)
        ) {
            // Project description (replaces category tags)
            if (project.description.isNotBlank()) {
                Text(
                    text = project.description,
                    fontSize = 14.sp,
                    color = IPBodyColor,
                    lineHeight = 22.sp
                )
            }
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
                        color = IPAccentDark
                    )
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = IPAccentDark, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun IPDiariesSection(
    diaries: List<Diary>,
    onDiaryClick: (String) -> Unit,
    onViewAllDiaries: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.tab_user_diaries),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = IPTitleColor
            )
            if (diaries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.show_all_diaries_count, diaries.size),
                    fontSize = 13.sp,
                    color = IPAccentDark,
                    modifier = Modifier.clickable { onViewAllDiaries() }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (diaries.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_user_diaries), fontSize = 14.sp, color = IPHintColor)
            }
        } else {
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
}

@Composable
private fun IPInstitutionSection(
    institutions: List<Institution>,
    onInstitutionClick: (String) -> Unit,
    onViewAllInstitutions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.tab_affiliated_institutions),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = IPTitleColor
            )
            if (institutions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.show_all_institutions_count, institutions.size),
                    fontSize = 13.sp,
                    color = IPAccentDark,
                    modifier = Modifier.clickable { onViewAllInstitutions() }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (institutions.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_certified_institutions), fontSize = 14.sp, color = IPHintColor)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                institutions.take(5).forEach { institution ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(IPCardBg)
                            .clickable { onInstitutionClick(institution.id) }
                            .padding(16.dp)
                    ) {
                        // Institution logo + name
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                Text(institution.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = IPTitleColor)
                                if (institution.isVerified) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Verified, contentDescription = null, tint = IPAccentDark, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.an_yan_certified), fontSize = 11.sp, color = IPAccentDark)
                                    }
                                }
                            }
                        }
                        // Address
                        if (institution.address.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = IPHintColor, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(institution.address, fontSize = 13.sp, color = IPBodyColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        // Rating
                        if (institution.rating > 0f) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Star, contentDescription = null, tint = IPRatingColor, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("%.1f (%d)".format(institution.rating, institution.reviewCount), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = IPTitleColor)
                            }
                        }
                        // View institution link
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.view_institution_detail),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = IPAccentDark
                            )
                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = IPAccentDark, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstitutionProjectBottomBar(
    onAiChatClick: () -> Unit,
    onConsultClick: () -> Unit,
    onBookClick: () -> Unit,
    isFavorited: Boolean = false,
    onFavoriteClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(IPCardBg)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Favorite button
        FavoriteButton(
            isFavorited = isFavorited,
            onClick = onFavoriteClick,
            size = 28.dp
        )
        // Chat with AI
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(IPCardBg)
                .border(1.dp, IPAccentDark, RoundedCornerShape(22.dp))
                .clickable { onAiChatClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.chat_with_ai), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = IPTitleColor, maxLines = 1, textAlign = TextAlign.Center)
        }
        // Consult institution
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(IPCardBg)
                .border(1.dp, IPAccentDark, RoundedCornerShape(22.dp))
                .clickable { onConsultClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.consult_institution), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = IPTitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        // Book project
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(IPAccentDark)
                .clickable { onBookClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.book_project), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun IPDoctorsSection(
    doctors: List<Doctor>,
    onDoctorClick: (String) -> Unit,
    onNavigateToAllDoctors: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        // Title row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.tab_bookable_doctors),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = IPTitleColor
            )
            if (doctors.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onNavigateToAllDoctors() }
                ) {
                    Text(
                        text = stringResource(R.string.show_all_doctors_count, doctors.size),
                        fontSize = 13.sp,
                        color = IPAccentDark
                    )
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = IPAccentDark,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (doctors.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(IPTagBg),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_bookable_doctors), fontSize = 14.sp, color = IPHintColor)
            }
        } else {
            // Vertical list of doctor cards
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                doctors.take(5).forEach { doctor ->
                    IPDoctorCard(
                        doctor = doctor,
                        onClick = { onDoctorClick(doctor.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun IPDoctorCard(
    doctor: Doctor,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(IPCardBg)
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Left: circular avatar
        if (doctor.avatar.isNotBlank()) {
            AsyncImage(
                model = doctor.avatar,
                contentDescription = doctor.name,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                onError = { /* fallback */ }
            )
        } else {
            PlaceholderImage(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
                text = doctor.name.take(1)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        // Middle: info column
        Column(modifier = Modifier.weight(1f)) {
            // Name + Title (bold, large)
            Text(
                text = "${doctor.name} ${doctor.title}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = IPTitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Certification description (first certificationTag)
            if (doctor.certificationTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = doctor.certificationTags.first(),
                    fontSize = 12.sp,
                    color = IPBodyColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Specialty tags row (gray rounded, scrollable, show up to 3 + "+N")
            if (doctor.specialties.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val visible = doctor.specialties.take(3)
                    val remaining = doctor.specialties.size - 3
                    visible.forEach { s ->
                        Text(
                            s, fontSize = 11.sp, color = IPBodyColor, maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(IPTagBg)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    if (remaining > 0) {
                        Text(
                            "+$remaining", fontSize = 11.sp, color = IPHintColor, maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(IPTagBg)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            // Metrics: post-op reviews | consultations
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.doctor_review_consult, doctor.reviewCount, doctor.consultationCount),
                fontSize = 12.sp,
                color = IPHintColor
            )
        }
    }
}
