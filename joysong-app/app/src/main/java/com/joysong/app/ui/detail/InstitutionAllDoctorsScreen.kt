package com.joysong.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.domain.model.Doctor
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.PlaceholderImage

private val PageBg = Color(0xFFF5F5F5)
private val CardBg = Color.White
private val TitleColor = Color(0xFF1A1A1A)
private val BodyColor = Color(0xFF666666)
private val HintColor = Color(0xFF999999)
private val AccentDark = Color(0xFF1A1A1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstitutionAllDoctorsScreen(
    institutionId: String,
    onBackClick: () -> Unit,
    onDoctorClick: (String) -> Unit,
    viewModel: InstitutionAllDoctorsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    LaunchedEffect(institutionId) { viewModel.load(institutionId) }

    Scaffold(
        topBar = {
            JoysongTopBar(title = stringResource(R.string.all_doctors_title), onBackClick = onBackClick)
        }
    ) { innerPadding ->
        when (val state = uiState) {
            is DetailUiState.Loading -> LoadingIndicator()
            is DetailUiState.Error -> ErrorView(message = state.message, onRetry = { viewModel.load(institutionId) })
            is DetailUiState.Success -> {
                val doctors = state.data
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh(institutionId) },
                    modifier = Modifier.fillMaxSize().padding(innerPadding).background(PageBg)
                ) {
                    if (doctors.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_doctors), fontSize = 14.sp, color = HintColor)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(doctors, key = { it.id }) { doctor ->
                                DoctorGridCard(doctor = doctor, onClick = { onDoctorClick(doctor.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DoctorGridCard(
    doctor: Doctor,
    onClick: () -> Unit,
    images: List<String> = emptyList()
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
            .clickable { onClick() }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (doctor.avatar.isNotBlank()) {
            AsyncImage(
                model = doctor.avatar,
                contentDescription = doctor.name,
                modifier = Modifier.size(64.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
                onError = { }
            )
        } else {
            PlaceholderImage(modifier = Modifier.size(64.dp).clip(CircleShape), text = doctor.name.take(1))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            doctor.name,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TitleColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            doctor.title,
            fontSize = 12.sp, color = BodyColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (doctor.isVerified) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Verified, contentDescription = null, tint = AccentDark, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(2.dp))
                Text(stringResource(R.string.an_yan_certified), fontSize = 10.sp, color = AccentDark)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            doctor.institutionName,
            fontSize = 11.sp, color = HintColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        // 3 small images at bottom
        val imgs = images.take(3)
        if (imgs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                imgs.forEach { url ->
                    AsyncImage(
                        model = url, contentDescription = null,
                        modifier = Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop, onError = { }
                    )
                }
            }
        }
    }
}
