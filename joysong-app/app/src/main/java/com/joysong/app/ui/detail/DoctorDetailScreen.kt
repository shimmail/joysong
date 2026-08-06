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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.joysong.app.ui.components.TagFilterBar
import coil.request.CachePolicy
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.joysong.app.R
import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.DoctorInstitutionProjectInfo
import com.joysong.app.domain.model.FavoriteType
import com.joysong.app.domain.model.Institution
import com.joysong.app.ui.components.DiaryCard
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.FavoriteButton
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.PlaceholderImage
import kotlinx.coroutines.launch

// Design spec colors (inline)
private val DPageBg = Color(0xFFF5F5F5)
private val DCardBg = Color.White
private val DTitleColor = Color(0xFF1A1A1A)
private val DBodyColor = Color(0xFF666666)
private val DHintColor = Color(0xFF999999)
private val DPriceColor = Color(0xFFE53935)
private val DRatingColor = Color(0xFFFF9800)
private val DTagBg = Color(0xFFF0F0F0)
private val DAccentDark = Color(0xFF1A1A1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorDetailScreen(
    doctorId: String,
    onBackClick: () -> Unit,
    onAiChatClick: (doctor: Doctor) -> Unit = {},
    onConsultClick: (doctor: Doctor) -> Unit = {},
    onProjectClick: (institutionId: String, projectId: String) -> Unit = { _, _ -> },
    onDiaryClick: (String) -> Unit = {},
    onInstitutionClick: (String) -> Unit = {},
    onNavigateToAllDiaries: (String) -> Unit = {},
    onNavigateToAllProjects: (String) -> Unit = {},
    onMessageClick: (String) -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel()
) {
    val doctorState by viewModel.doctor.collectAsState()
    val doctorProjects by viewModel.doctorProjects.collectAsState()
    val doctorDiaries by viewModel.doctorDiaries.collectAsState()
    val doctorInstitution by viewModel.doctorInstitution.collectAsState()
    val doctorInstitutions by viewModel.doctorInstitutions.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    LaunchedEffect(doctorId) {
        viewModel.loadDoctor(doctorId)
        viewModel.checkGenericFavoriteStatus(FavoriteType.DOCTOR, doctorId)
    }

    // Check favorite status for each project when projects are loaded
    LaunchedEffect(doctorProjects) {
        doctorProjects.forEach { project ->
            viewModel.checkProjectFavorite(project.projectId)
        }
    }

    val isFavorited by viewModel.genericFavorited
    val projectFavoriteStates by viewModel.projectFavoriteStates

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.doctor_detail),
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            when (doctorState) {
                is DetailUiState.Success -> {
                    val doctor = (doctorState as DetailUiState.Success<Doctor>).data
                    DoctorBottomBar(
                        onAiChatClick = { onAiChatClick(doctor) },
                        onConsultClick = { onConsultClick(doctor) },
                        isFavorited = isFavorited,
                        onFavoriteClick = {
                            viewModel.toggleGenericFavorite(
                                FavoriteType.DOCTOR, doctorId, doctor.name, doctor.avatar
                            )
                        }
                    )
                }
                else -> {}
            }
        }
    ) { innerPadding ->
        when (doctorState) {
            is DetailUiState.Loading -> LoadingIndicator()
            is DetailUiState.Error -> ErrorView(
                message = (doctorState as DetailUiState.Error).message,
                onRetry = { viewModel.loadDoctor(doctorId) }
            )
            is DetailUiState.Success -> {
                val doctor = (doctorState as DetailUiState.Success<Doctor>).data
                val lazyListState = rememberLazyListState()
                val coroutineScope = rememberCoroutineScope()
                val pullRefreshState = rememberPullToRefreshState()
                // Section indices: 0=Header, 1=NavBar, 2=Qualification, 3=Projects, 4=Diaries, 5=Institution
                val navSectionIndices = remember { listOf(2, 3, 4, 5) }

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refreshDoctor(doctorId) },
                    state = pullRefreshState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(DPageBg)
                ) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 0: Header
                        item {
                            DocHeader(
                                doctor = doctor,
                                primaryInstitution = doctorInstitutions.firstOrNull() ?: doctorInstitution,
                                onInstitutionClick = onInstitutionClick,
                                onMessageClick = onMessageClick
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 1: Anchor Navigation Bar
                        item {
                            DoctorAnchorNavBar(
                                onSectionClick = { index ->
                                    coroutineScope.launch {
                                        lazyListState.animateScrollToItem(
                                            navSectionIndices.getOrNull(index) ?: index
                                        )
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 2: Qualification Section
                        item {
                            DocQualificationTab(doctor)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 3: Projects Section
                        item {
                            DocProjectsTab(
                                doctorProjects, onProjectClick,
                                onNavigateToAllProjects = { onNavigateToAllProjects(doctorId) },
                                projectFavoriteStates = projectFavoriteStates,
                                onProjectFavoriteClick = { project ->
                                    viewModel.toggleProjectFavorite(
                                        project.projectId, project.projectName, project.coverImage
                                    )
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 4: Diaries Section (horizontal layout)
                        item {
                            DocDiariesTab(doctorDiaries, onDiaryClick, onNavigateToAllDiaries = { onNavigateToAllDiaries(doctorId) })
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // 5: Institution Section
                        item {
                            DocInstitutionTab(doctorInstitutions.ifEmpty { listOfNotNull(doctorInstitution) }, onInstitutionClick)
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DoctorAnchorNavBar(onSectionClick: (Int) -> Unit) {
    val navTitles = listOf(
        stringResource(R.string.doctor_profile_materials),
        stringResource(R.string.tab_bookable_projects),
        stringResource(R.string.tab_user_diaries),
        stringResource(R.string.tab_clinic_affiliations)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DCardBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        navTitles.forEachIndexed { index, title ->
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
                    color = DAccentDark,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun DocHeader(
    doctor: Doctor,
    primaryInstitution: Institution?,
    onInstitutionClick: (String) -> Unit,
    onMessageClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DCardBg)
            .padding(16.dp)
    ) {
        // Avatar centered at top
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (doctor.avatar.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(doctor.avatar)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .crossfade(true)
                        .build(),
                    contentDescription = doctor.name,
                    modifier = Modifier.size(80.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                PlaceholderImage(
                    modifier = Modifier.size(80.dp).clip(CircleShape),
                    text = stringResource(R.string.doctor_placeholder)
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Content left-aligned below avatar
        Column(modifier = Modifier.fillMaxWidth()) {
            // Verified badge
            if (doctor.isVerified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Verified, contentDescription = null, tint = DAccentDark, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.an_yan_certified) + " · " + stringResource(R.string.doctor_certified_subtitle),
                        fontSize = 12.sp, color = DAccentDark
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
            // Name + title
            Text(
                text = "${doctor.name}  ${doctor.title}",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DTitleColor
            )
            // Certification tags
            if (doctor.certificationTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    doctor.certificationTags.forEach { tag ->
                        Text(
                            text = tag,
                            fontSize = 12.sp,
                            color = DAccentDark,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(DTagBg)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            // Specialties tags
            if (doctor.specialties.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    doctor.specialties.forEach { specialty ->
                        Text(
                            text = specialty,
                            fontSize = 12.sp,
                            color = DAccentDark,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(DTagBg)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            // Bio (doctor introduction)
            if (doctor.bio.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = doctor.bio,
                    fontSize = 12.sp,
                    color = DHintColor,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Institution name (clickable)
            if (primaryInstitution != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onInstitutionClick(primaryInstitution.id) }
                ) {
                    Icon(Icons.Outlined.Business, contentDescription = null, tint = DHintColor, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = primaryInstitution.name,
                        fontSize = 12.sp,
                        color = DBodyColor,
                        textDecoration = TextDecoration.Underline
                    )
                }
            }
            // Reviews + consultations + cases + rating
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.doctor_review_consult, doctor.reviewCount, doctor.consultationCount),
                    fontSize = 13.sp, color = DBodyColor
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.case_count_format, doctor.caseCount),
                    fontSize = 13.sp, color = DBodyColor
                )
                // Rating score
                Spacer(modifier = Modifier.width(12.dp))
                Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "%.1f".format(doctor.rating),
                    fontSize = 13.sp, color = DTitleColor, fontWeight = FontWeight.SemiBold
                )
            }
            // 发私信按钮
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { onMessageClick(doctor.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(22.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "发私信",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun DocQualificationTab(doctor: Doctor) {
    var showImageViewer by remember { mutableStateOf(false) }
    var viewerImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerInitialIndex by remember { mutableStateOf(0) }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.doctor_strength), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DTitleColor)
            Text(stringResource(R.string.doctor_self_provided_materials), fontSize = 13.sp, color = DAccentDark)
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Credentials text
        if (doctor.credentials.isNotBlank()) {
            doctor.credentials.split("\n").filter { it.isNotBlank() }.forEach { p ->
                Text(p, fontSize = 14.sp, color = DBodyColor, lineHeight = 22.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
        } else {
            Text(stringResource(R.string.no_content), fontSize = 14.sp, color = DHintColor)
        }
        // Credential images - horizontal scroll, click to view full size
        val imageList = remember(doctor.credentialImages) {
            if (doctor.credentialImages.isBlank()) emptyList() else doctor.credentialImages.split(",").filter { it.isNotBlank() }
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
        FullscreenImageViewer(
            images = viewerImages,
            initialIndex = viewerInitialIndex,
            onDismiss = { showImageViewer = false }
        )
    }
}

@Composable
private fun DocProjectsTab(
    projects: List<DoctorInstitutionProjectInfo>,
    onProjectClick: (institutionId: String, projectId: String) -> Unit,
    onNavigateToAllProjects: () -> Unit = {},
    projectFavoriteStates: Map<String, Boolean> = emptyMap(),
    onProjectFavoriteClick: (DoctorInstitutionProjectInfo) -> Unit = {}
) {
    if (projects.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.doctor_projects_empty), fontSize = 14.sp, color = DHintColor)
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
                stringResource(R.string.tab_bookable_projects),
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DTitleColor
            )
            Text(
                stringResource(R.string.all_count, projects.size),
                fontSize = 13.sp, color = DAccentDark,
                modifier = Modifier.clickable { onNavigateToAllProjects() }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        projects.take(5).forEach { project ->
            DocInstitutionProjectCard(
                    project = project,
                    onClick = { onProjectClick(project.institutionId, project.projectId) },
                    isFavorited = projectFavoriteStates[project.projectId] == true,
                    onFavoriteClick = { onProjectFavoriteClick(project) }
                )
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun DocInstitutionProjectCard(
    project: DoctorInstitutionProjectInfo,
    onClick: () -> Unit,
    isFavorited: Boolean = false,
    onFavoriteClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DCardBg)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (project.coverImage.isNotBlank()) {
            AsyncImage(
                model = project.coverImage, contentDescription = project.projectName,
                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            PlaceholderImage(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)), text = stringResource(R.string.project_placeholder))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(project.projectName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DTitleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(4.dp))
            Text(project.institutionName, fontSize = 12.sp, color = DBodyColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.doctor_price_format, project.price),
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = DPriceColor
                )
                if (project.originalPrice != null && project.originalPrice > project.price) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.doctor_original_price_format, project.originalPrice),
                        fontSize = 12.sp, color = DHintColor,
                        textDecoration = TextDecoration.LineThrough
                    )
                }
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
private fun DocDiariesTab(
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
            Text(
                stringResource(R.string.tab_user_diaries),
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DTitleColor
            )
            if (diaries.isNotEmpty()) {
                Text(
                    stringResource(R.string.all_count, diaries.size),
                    fontSize = 13.sp, color = DAccentDark,
                    modifier = Modifier.clickable { onNavigateToAllDiaries() }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (diaries.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.doctor_diaries_empty), fontSize = 14.sp, color = DHintColor)
            }
        } else {
            // Horizontal scrolling diary cards
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
private fun DocInstitutionTab(institutions: List<Institution>, onInstitutionClick: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.tab_clinic_affiliations),
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DTitleColor,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (institutions.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.doctor_institution_empty), fontSize = 14.sp, color = DHintColor)
            }
            return
        }

        institutions.forEachIndexed { index, institution ->
            DocInstitutionCard(institution, onInstitutionClick)
            if (index < institutions.lastIndex) Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DocInstitutionCard(institution: Institution, onInstitutionClick: (String) -> Unit) {
    Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DCardBg)
                .clickable { onInstitutionClick(institution.id) }
                .padding(16.dp)
        ) {
            // Name + verified
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(institution.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DTitleColor, modifier = Modifier.weight(1f))
                if (institution.isVerified) {
                    Icon(Icons.Outlined.Verified, contentDescription = null, tint = DAccentDark, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.an_yan_certified), fontSize = 12.sp, color = DAccentDark)
                }
            }
            // Address
            val fullAddress = buildString {
                if (institution.city.isNotBlank()) append(institution.city)
                if (institution.address.isNotBlank()) append(institution.address)
            }
            if (fullAddress.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = DHintColor, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(fullAddress, fontSize = 13.sp, color = DBodyColor)
                }
            }
            // Rating
            if (institution.rating > 0f) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Star, contentDescription = null, tint = DRatingColor, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("%.1f".format(institution.rating), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DTitleColor)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.reviews_count, institution.reviewCount), fontSize = 12.sp, color = DBodyColor)
                }
            }
    }
}

@Composable
private fun FullscreenImageViewer(
    images: List<String>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit
) {
    var currentIndex by remember { mutableStateOf(initialIndex) }
    val pagerState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }

            // Horizontal scrolling images
            LazyRow(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(images) { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            // Page indicator
            if (images.size > 1) {
                Text(
                    text = "${currentIndex + 1} / ${images.size}",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                )
            }
        }
    }
}

@Composable
private fun DoctorBottomBar(
    onAiChatClick: () -> Unit,
    onConsultClick: () -> Unit,
    isFavorited: Boolean = false,
    onFavoriteClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DCardBg)
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
                .background(DCardBg)
                .border(1.dp, DAccentDark, RoundedCornerShape(22.dp))
                .clickable { onAiChatClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.chat_with_ai), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DTitleColor, maxLines = 1, textAlign = TextAlign.Center)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(DAccentDark)
                .clickable { onConsultClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.consult_doctor), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}
