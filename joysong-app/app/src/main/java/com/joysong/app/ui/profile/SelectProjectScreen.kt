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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.domain.model.InstitutionProjectItem
import com.joysong.app.domain.model.Order
import com.joysong.app.domain.model.ProjectWithInstitutions
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

/**
 * Selection result emitted from SelectProjectScreen.
 * - When from an order: orderId, projectId, institutionId, doctorId, projectName, institutionName
 * - When from a project: projectId, projectName, institutionId, institutionName, institutionProjectId
 */
data class ProjectSelection(
    val orderId: String = "",
    val projectId: String,
    val institutionId: String,
    val institutionProjectId: String = "",
    val doctorId: String = "",
    val doctorName: String = "",
    val projectName: String,
    val institutionName: String
)

@Composable
fun SelectProjectScreen(
    onBackClick: () -> Unit,
    onConfirm: (ProjectSelection) -> Unit,
    viewModel: EntityPickerViewModel = hiltViewModel()
) {
    val projectsWithInstitutions by viewModel.projectsWithInstitutions.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var orderQuery by remember { mutableStateOf("") }
    var projectQuery by remember { mutableStateOf("") }
    var selectedOrder by remember { mutableStateOf<Order?>(null) }
    // Track selected institution project: Pair<ProjectWithInstitutions, InstitutionProjectItem>
    var selectedIpPair by remember { mutableStateOf<Pair<ProjectWithInstitutions, InstitutionProjectItem>?>(null) }

    val tabs = listOf(
        stringResource(R.string.tab_related_orders),
        stringResource(R.string.tab_related_projects)
    )

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.select_project_title),
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            ConfirmButton(
                enabled = if (selectedTab == 0) selectedOrder != null else selectedIpPair != null,
                onClick = {
                    if (selectedTab == 0) {
                        selectedOrder?.let { order ->
                            onConfirm(
                                ProjectSelection(
                                    orderId = order.id,
                                    projectId = order.projectId,
                                    institutionId = order.institutionId,
                                    doctorId = order.doctorId,
                                    doctorName = order.doctorName,
                                    projectName = order.projectName,
                                    institutionName = order.institutionName
                                )
                            )
                        }
                    } else {
                        selectedIpPair?.let { (pwi, ip) ->
                            onConfirm(
                                ProjectSelection(
                                    projectId = pwi.project.id,
                                    institutionId = ip.institutionId,
                                    institutionProjectId = ip.id,
                                    projectName = ip.name.ifBlank { pwi.project.name },
                                    institutionName = ip.institutionName
                                )
                            )
                        }
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
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Surface,
                contentColor = PrimaryDark,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.Indicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = PrimaryDark
                        )
                    }
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                color = if (selectedTab == index) PrimaryDark else TextSecondary,
                                fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            if (isLoading && (if (selectedTab == 0) orders else projectsWithInstitutions).isEmpty()) {
                LoadingIndicator()
            } else if (selectedTab == 0) {
                // Orders tab
                SearchField(
                    query = orderQuery,
                    hint = stringResource(R.string.search_order_hint),
                    onQueryChange = { orderQuery = it }
                )
                val filteredOrders = remember(orders, orderQuery) {
                    if (orderQuery.isBlank()) orders
                    else orders.filter {
                        it.projectName.contains(orderQuery, ignoreCase = true) ||
                            it.institutionName.contains(orderQuery, ignoreCase = true) ||
                            it.orderNo.contains(orderQuery, ignoreCase = true)
                    }
                }
                if (filteredOrders.isEmpty()) {
                    EmptyView()
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(filteredOrders, key = { _, o -> o.id }) { _, order ->
                            OrderPickerItem(
                                order = order,
                                selected = selectedOrder?.id == order.id,
                                onClick = { selectedOrder = order }
                            )
                        }
                    }
                }
            } else {
                // Projects tab — shows projects with institution project sub-items
                SearchField(
                    query = projectQuery,
                    hint = stringResource(R.string.search_project_hint),
                    onQueryChange = {
                        projectQuery = it
                        viewModel.loadProjects(it)
                    }
                )
                if (projectsWithInstitutions.isEmpty()) {
                    EmptyView()
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(projectsWithInstitutions, key = { it.project.id }) { pwi ->
                            ProjectGroupItem(
                                projectWithInstitutions = pwi,
                                selectedIpId = selectedIpPair?.second?.id,
                                onIpClick = { ip ->
                                    selectedIpPair = if (selectedIpPair?.second?.id == ip.id) null else (pwi to ip)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectGroupItem(
    projectWithInstitutions: ProjectWithInstitutions,
    selectedIpId: String?,
    onIpClick: (InstitutionProjectItem) -> Unit
) {
    val pwi = projectWithInstitutions
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .padding(12.dp)
    ) {
        // Project header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (pwi.project.coverImage.isNotBlank()) {
                    AsyncImage(
                        model = pwi.project.coverImage,
                        contentDescription = pwi.project.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = pwi.project.name.take(1),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pwi.project.name,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (pwi.project.tags.isNotEmpty()) {
                    Text(
                        text = pwi.project.tags.joinToString(" · "),
                        color = TextHint,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Institution project sub-items
        if (pwi.institutionProjects.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            pwi.institutionProjects.forEach { ip ->
                val isSelected = selectedIpId == ip.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) PrimaryDark.copy(alpha = 0.08f) else Color.Transparent)
                        .then(
                            if (isSelected) Modifier.border(1.dp, PrimaryDark, RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        .clickable { onIpClick(ip) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ip.name.ifBlank { ip.institutionName },
                            fontSize = 13.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (ip.institutionCity.isNotBlank()) {
                            Text(
                                text = listOf(ip.institutionName, ip.institutionCity).filter { it.isNotBlank() }.joinToString(" · "),
                                fontSize = 11.sp,
                                color = TextHint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(
                        text = "¥${ip.price.toInt()}",
                        fontSize = 13.sp,
                        color = PrimaryDark,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) PrimaryDark else Color.Transparent)
                            .border(1.5.dp, if (isSelected) PrimaryDark else TextHint, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(TextOnPrimary)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderPickerItem(
    order: Order,
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
            if (order.coverImage.isNotBlank()) {
                AsyncImage(
                    model = order.coverImage,
                    contentDescription = order.projectName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = order.projectName.take(1),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = order.projectName,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = order.institutionName,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "¥${order.price}",
                color = PrimaryDark,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        SelectionDot(selected = selected)
    }
}

@Composable
internal fun SelectionDot(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) PrimaryDark else Color.Transparent)
            .border(
                1.5.dp,
                if (selected) PrimaryDark else TextHint,
                CircleShape
            ),
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
