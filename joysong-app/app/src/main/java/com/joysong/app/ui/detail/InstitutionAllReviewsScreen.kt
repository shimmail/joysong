package com.joysong.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.domain.model.Review
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.ReportDialog
import com.joysong.app.ui.report.ReportViewModel

private val PageBg = Color(0xFFF5F5F5)
private val HintColor = Color(0xFF999999)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstitutionAllReviewsScreen(
    institutionId: String,
    onBackClick: () -> Unit,
    viewModel: InstitutionAllReviewsViewModel = hiltViewModel(),
    reportViewModel: ReportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    LaunchedEffect(institutionId) { viewModel.load(institutionId) }

    Scaffold(
        topBar = {
            JoysongTopBar(title = stringResource(R.string.all_reviews_title), onBackClick = onBackClick)
        }
    ) { innerPadding ->
        when (val state = uiState) {
            is DetailUiState.Loading -> LoadingIndicator()
            is DetailUiState.Error -> ErrorView(message = state.message, onRetry = { viewModel.load(institutionId) })
            is DetailUiState.Success -> {
                val reviews = state.data
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh(institutionId) },
                    modifier = Modifier.fillMaxSize().padding(innerPadding).background(PageBg)
                ) {
                    if (reviews.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.institution_reviews_empty), fontSize = 14.sp, color = HintColor)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(reviews, key = { it.id }) { review ->
                                AllReviewCard(review = review, onReport = {
                                    reportViewModel.showReport("review", review.id)
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    ReportDialog(
        reportViewModel = reportViewModel,
        onDismiss = { reportViewModel.hideReport() }
    )
}

@Composable
private fun AllReviewCard(review: Review, onReport: () -> Unit = {}) {
    val CardBg = Color.White
    val TitleColor = Color(0xFF1A1A1A)
    val BodyColor = Color(0xFF666666)
    val HintColorLocal = Color(0xFF999999)
    val AccentDark = Color(0xFF1A1A1A)
    val RatingColor = Color(0xFFFF9800)
    val TagBg = Color(0xFFF0F0F0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(5) { index ->
                Icon(
                    Icons.Outlined.Star,
                    contentDescription = null,
                    tint = if (index < review.rating) RatingColor else HintColorLocal,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(review.content, fontSize = 14.sp, color = BodyColor, lineHeight = 22.sp)
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
        if (review.images.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                review.images.take(3).forEach { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                        onError = { /* fallback */ }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(review.userName.ifBlank { review.userId.take(6) }, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TitleColor)
            Spacer(modifier = Modifier.width(8.dp))
            Text(review.createdAt, fontSize = 12.sp, color = HintColorLocal)
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onReport, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = stringResource(R.string.report),
                    tint = HintColorLocal,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
