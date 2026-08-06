package com.joysong.app.ui.profile

import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.outlined.HelpCenter
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.joysong.app.R
import com.joysong.app.domain.model.User
import com.joysong.app.ui.components.DefaultUserAvatar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary

@Composable
fun ProfileScreen(
    onOrdersClick: () -> Unit,
    onDiariesClick: () -> Unit,
    onJourneyClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onCustomerServiceClick: () -> Unit,
    onHelpClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAccountSecurityClick: () -> Unit,
    onEditProfileClick: () -> Unit,
    onLogout: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val user by viewModel.user.collectAsState()
    var showLogoutDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 当页面恢复可见时刷新用户数据（从编辑页返回时头像能即时更新）
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.loadUser()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            UserHeaderCard(
                user = user,
                onEditProfileClick = onEditProfileClick
            )
        }

        item {
            ServiceGrid(
                onOrdersClick = onOrdersClick,
                onDiariesClick = onDiariesClick,
                onJourneyClick = onJourneyClick,
                onFavoritesClick = onFavoritesClick
            )
        }

        item {
            SectionCard(
                items = listOf(
                    MenuItemData(
                        Icons.Outlined.Phone,
                        stringResource(R.string.customer_service),
                        stringResource(R.string.customer_service_desc),
                        onCustomerServiceClick
                    ),
                    MenuItemData(
                        Icons.Outlined.HelpCenter,
                        stringResource(R.string.help_feedback),
                        stringResource(R.string.help_feedback_desc),
                        onHelpClick
                    )
                )
            )
        }

        item {
            SectionCard(
                items = listOf(
                    MenuItemData(
                        Icons.Outlined.Settings,
                        stringResource(R.string.settings),
                        "",
                        onSettingsClick
                    ),
                    MenuItemData(
                        Icons.Outlined.ListAlt,
                        stringResource(R.string.about_us),
                        "",
                        onAboutClick
                    ),
                    MenuItemData(
                        Icons.Outlined.Lock,
                        stringResource(R.string.account_security),
                        "",
                        onAccountSecurityClick
                    )
                )
            )
        }

        item {
            Button(
                onClick = { showLogoutDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Surface)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.logout),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.logout_title)) },
            text = { Text(stringResource(R.string.logout_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                        onLogout()
                    }
                ) {
                    Text(stringResource(R.string.confirm), color = PrimaryDark)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun UserHeaderCard(
    user: User?,
    onEditProfileClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val avatar = user?.avatar
                Log.d("ProfileScreen", "Avatar URL: $avatar")
                if (!avatar.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(avatar)
                            .crossfade(true)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .listener(
                                onError = { _, result ->
                                    Log.e("ProfileScreen", "头像加载失败: ${result.throwable.message}")
                                },
                                onSuccess = { _, _ ->
                                    Log.d("ProfileScreen", "头像加载成功")
                                }
                            )
                            .build(),
                        contentDescription = "头像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    DefaultUserAvatar(
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = user?.nickname?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.not_logged_in),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                text = user?.phone?.takeIf { it.isNotBlank() } ?: "",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onEditProfileClick,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = TextOnPrimary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.edit_profile),
                    color = TextOnPrimary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun ServiceGrid(
    onOrdersClick: () -> Unit,
    onDiariesClick: () -> Unit,
    onJourneyClick: () -> Unit,
    onFavoritesClick: () -> Unit
) {
    val items = listOf(
        GridItemData(
            Icons.Outlined.ListAlt,
            stringResource(R.string.my_orders),
            PrimaryDark,
            onOrdersClick
        ),
        GridItemData(
            Icons.Outlined.Article,
            stringResource(R.string.my_diaries),
            PrimaryDark,
            onDiariesClick
        ),
        GridItemData(
            Icons.Outlined.TravelExplore,
            stringResource(R.string.beauty_journey),
            PrimaryDark,
            onJourneyClick
        ),
        GridItemData(
            Icons.Outlined.FavoriteBorder,
            stringResource(R.string.my_favorites),
            PrimaryDark,
            onFavoritesClick
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.my_services),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                items.take(2).forEach { item ->
                    GridItem(item = item, modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                items.drop(2).forEach { item ->
                    GridItem(item = item, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GridItem(
    item: GridItemData,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = item.onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(item.iconColor.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = item.iconColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.title,
            fontSize = 13.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

private data class GridItemData(
    val icon: ImageVector,
    val title: String,
    val iconColor: androidx.compose.ui.graphics.Color,
    val onClick: () -> Unit
)

private data class MenuItemData(
    val icon: ImageVector,
    val title: String,
    val subtitle: String = "",
    val onClick: () -> Unit
)

@Composable
private fun SectionCard(items: List<MenuItemData>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            items.forEachIndexed { index, item ->
                MenuListItem(item = item)
                if (index < items.size - 1) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Background)
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuListItem(item: MenuItemData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = PrimaryDark
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                fontSize = 15.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = TextHint
        )
    }
}
