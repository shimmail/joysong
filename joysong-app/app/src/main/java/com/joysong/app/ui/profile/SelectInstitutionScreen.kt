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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.domain.model.Institution
import com.joysong.app.ui.components.EmptyView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary

/**
 * Selection result emitted from SelectInstitutionScreen.
 */
data class InstitutionSelection(
    val institutionId: String,
    val institutionName: String
)

@Composable
fun SelectInstitutionScreen(
    onBackClick: () -> Unit,
    onConfirm: (InstitutionSelection) -> Unit,
    viewModel: EntityPickerViewModel = hiltViewModel()
) {
    val institutions by viewModel.institutions.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selectedInstitution by remember { mutableStateOf<Institution?>(null) }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.select_institution_title),
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            ConfirmButton(
                enabled = selectedInstitution != null,
                onClick = {
                    selectedInstitution?.let { inst ->
                        onConfirm(
                            InstitutionSelection(
                                institutionId = inst.id,
                                institutionName = inst.name
                            )
                        )
                    }
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
                hint = stringResource(R.string.search_institution_hint),
                onQueryChange = {
                    query = it
                    viewModel.loadInstitutions(it)
                }
            )
            when {
                isLoading && institutions.isEmpty() -> LoadingIndicator()
                institutions.isEmpty() -> EmptyView()
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(institutions, key = { it.id }) { institution ->
                        InstitutionPickerItem(
                            institution = institution,
                            selected = selectedInstitution?.id == institution.id,
                            onClick = { selectedInstitution = institution }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstitutionPickerItem(
    institution: Institution,
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
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (institution.coverImage.isNotBlank()) {
                AsyncImage(
                    model = institution.coverImage,
                    contentDescription = institution.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = institution.name.take(1),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = institution.name,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (institution.address.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = institution.address,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    tint = PrimaryDark,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = String.format("%.1f", institution.rating),
                    color = PrimaryDark,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (institution.tags.isNotBlank()) {
                    Text(
                        text = institution.tags.split(",").take(2).joinToString(" · "),
                        color = TextHint,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        SelectionDot(selected = selected)
    }
}
