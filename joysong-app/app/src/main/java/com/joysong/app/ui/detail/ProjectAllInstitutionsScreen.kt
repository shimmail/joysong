package com.joysong.app.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.InstitutionProject
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator

private val PageBg = Color(0xFFF5F5F5)
private val CardBg = Color.White
private val TitleColor = Color(0xFF1A1A1A)
private val BodyColor = Color(0xFF666666)
private val HintColor = Color(0xFF999999)
private val PriceColor = Color(0xFFE53935)
private val RatingColor = Color(0xFFFF9800)
private val AccentDark = Color(0xFF1A1A1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectAllInstitutionsScreen(
    projectId: String,
    onBackClick: () -> Unit,
    onInstitutionClick: (String) -> Unit,
    onInstitutionProjectClick: (String, String) -> Unit = { _, _ -> },
    viewModel: ProjectAllInstitutionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    LaunchedEffect(projectId) {
        viewModel.load(projectId)
    }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.tab_affiliated_institutions),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            is DetailUiState.Loading -> LoadingIndicator()
            is DetailUiState.Error -> ErrorView(
                message = state.message,
                onRetry = { viewModel.load(projectId) }
            )
            is DetailUiState.Success -> {
                val institutionPairs = state.data
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh(projectId) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(PageBg)
                ) {
                    if (institutionPairs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.no_certified_institutions),
                                fontSize = 14.sp,
                                color = HintColor
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                institutionPairs,
                                key = { it.second.id }
                            ) { (instProject, institution) ->
                                AllInstitutionRow(
                                    institution = institution,
                                    instProject = instProject,
                                    onInstitutionClick = { onInstitutionClick(institution.id) },
                                    onProjectClick = { onInstitutionProjectClick(institution.id, instProject.projectId) }
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
private fun AllInstitutionRow(
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
            .background(CardBg)
    ) {
        // Institution info row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onInstitutionClick() }
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (institution.coverImage.isNotBlank()) {
                AsyncImage(
                    model = institution.coverImage,
                    contentDescription = institution.name,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                    onError = { }
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (instProject.name.isNotBlank()) {
                        Text(instProject.name, fontSize = 13.sp, color = TitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        institution.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (institution.isVerified) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Filled.Verified, contentDescription = null, tint = AccentDark, modifier = Modifier.size(14.dp))
                    }
                }
                if (institution.address.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = HintColor, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(institution.address, fontSize = 12.sp, color = BodyColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (institution.rating > 0f) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Star, contentDescription = null, tint = RatingColor, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            "%.1f (%d)".format(institution.rating, institution.reviewCount),
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TitleColor
                        )
                    }
                }
            }
        }
        // Expand/collapse toggle
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
                color = AccentDark,
                fontWeight = FontWeight.Medium
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = AccentDark
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
                        color = PriceColor
                    )
                    if (instProject.originalPrice != null && instProject.originalPrice > instProject.price) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.inst_project_original_price_format, instProject.originalPrice),
                            fontSize = 12.sp,
                            color = HintColor,
                            textDecoration = TextDecoration.LineThrough
                        )
                    }
                    if (instProject.salesCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sales_count_format, instProject.salesCount),
                            fontSize = 11.sp,
                            color = HintColor
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.book_project),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AccentDark
                    )
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AccentDark, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}
