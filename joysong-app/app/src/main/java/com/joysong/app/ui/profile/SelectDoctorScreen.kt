package com.joysong.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.joysong.app.R
import com.joysong.app.domain.model.Doctor
import com.joysong.app.ui.components.EmptyView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary

@Composable
fun SelectDoctorScreen(
    onBackClick: () -> Unit,
    onConfirm: (Doctor) -> Unit,
    institutionProjectId: String? = null,
    viewModel: EntityPickerViewModel = hiltViewModel()
) {
    val doctors by viewModel.doctors.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selectedDoctor by remember { mutableStateOf<Doctor?>(null) }

    // Load doctors filtered by institution project, or all if no institution project specified
    LaunchedEffect(institutionProjectId) {
        if (!institutionProjectId.isNullOrBlank()) {
            viewModel.loadInstitutionProjectDoctors(institutionProjectId)
        } else {
            viewModel.loadDoctors()
        }
    }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.select_doctor_title),
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            ConfirmButton(
                enabled = selectedDoctor != null,
                onClick = {
                    selectedDoctor?.let { doctor -> onConfirm(doctor) }
                }
            )
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SearchField(
                query = query,
                hint = stringResource(R.string.search_doctor_hint),
                onQueryChange = {
                    query = it
                    viewModel.loadDoctors(it)
                }
            )
            when {
                isLoading && doctors.isEmpty() -> LoadingIndicator()
                doctors.isEmpty() -> EmptyView()
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(doctors, key = { it.id }) { doctor ->
                        DoctorPickerItem(
                            doctor = doctor,
                            selected = selectedDoctor?.id == doctor.id,
                            onClick = { selectedDoctor = doctor }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DoctorPickerItem(
    doctor: Doctor,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .then(
                if (selected) Modifier.border(1.dp, PrimaryDark, RoundedCornerShape(12.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (doctor.avatar.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(doctor.avatar)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build(),
                    contentDescription = doctor.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = doctor.name.take(1),
                    color = TextSecondary,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = doctor.name,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (doctor.title.isNotBlank()) {
                Text(
                    text = doctor.title,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (doctor.specialties.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = doctor.specialties.joinToString(" · "),
                    color = TextHint,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (selected) PrimaryDark else Color.Transparent)
                .border(1.5.dp, if (selected) PrimaryDark else TextHint, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(TextOnPrimary)
                )
            }
        }
    }
}

/**
 * Shared confirm button style used across picker screens.
 */
@Composable
internal fun ConfirmButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryDark,
                contentColor = TextOnPrimary,
                disabledContainerColor = SurfaceVariant,
                disabledContentColor = TextHint
            )
        ) {
            Text(
                text = stringResource(R.string.confirm),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Shared search field style used across picker screens.
 */
@Composable
internal fun SearchField(
    query: String,
    hint: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(hint, color = TextHint) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextHint) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PrimaryDark,
            unfocusedBorderColor = TextHint,
            cursorColor = PrimaryDark
        )
    )
}
