package com.joysong.app.ui.coupon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.joysong.app.R
import com.joysong.app.domain.model.UserCoupon
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Error
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.PrimaryLight
import com.joysong.app.ui.theme.Secondary
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import com.joysong.app.ui.theme.Warning

@Composable
fun CouponListScreen(
    onBackClick: () -> Unit,
    onCouponSelected: (UserCoupon) -> Unit,
    viewModel: CouponListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val filteredCoupons = viewModel.getFilteredCoupons()

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.my_coupons_title),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Background)
        ) {
            // Tab row
            ScrollableTabRow(
                selectedTabIndex = uiState.selectedTab.ordinal,
                containerColor = Surface,
                contentColor = PrimaryDark,
                edgePadding = 16.dp,
                indicator = { tabPositions ->
                    if (uiState.selectedTab.ordinal < tabPositions.size) {
                        SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[uiState.selectedTab.ordinal]),
                            color = PrimaryDark
                        )
                    }
                },
                divider = {}
            ) {
                CouponTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                text = when (tab) {
                                    CouponTab.ALL -> stringResource(R.string.coupon_tab_all)
                                    CouponTab.AVAILABLE -> stringResource(R.string.coupon_tab_available)
                                    CouponTab.USED -> stringResource(R.string.coupon_tab_used)
                                    CouponTab.EXPIRED -> stringResource(R.string.coupon_tab_expired)
                                },
                                fontSize = 14.sp,
                                fontWeight = if (uiState.selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            when {
                uiState.isLoading -> LoadingIndicator()
                uiState.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.error ?: stringResource(R.string.load_failed),
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.loadCoupons() },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                            ) {
                                Text(stringResource(R.string.retry), color = TextOnPrimary)
                            }
                        }
                    }
                }
                filteredCoupons.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.coupon_empty),
                            color = TextHint,
                            fontSize = 15.sp
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(4.dp)) }
                        items(filteredCoupons) { coupon ->
                            CouponCard(
                                coupon = coupon,
                                isUsable = viewModel.isCouponUsable(coupon),
                                onUseClick = {
                                    if (viewModel.isCouponUsable(coupon)) {
                                        onCouponSelected(coupon)
                                    }
                                }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(4.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CouponCard(
    coupon: UserCoupon,
    isUsable: Boolean,
    onUseClick: () -> Unit
) {
    val isAvailable = coupon.status == "UNUSED" && isUsable
    val isUsed = coupon.status == "USED"
    val isExpired = coupon.status == "EXPIRED"

    val cardBorderColor = when {
        isAvailable -> Primary
        isUsed -> SurfaceVariant
        isExpired -> SurfaceVariant
        else -> SurfaceVariant
    }

    val discountColor = when {
        isAvailable -> PrimaryDark
        else -> TextHint
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, cardBorderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: discount value
            Column(
                modifier = Modifier.width(90.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (coupon.couponType == "PERCENTAGE") {
                    val discount = coupon.discountValue
                    Text(
                        text = stringResource(R.string.coupon_percentage_format, discount.toInt()),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = discountColor
                    )
                } else {
                    Text(
                        text = "¥${coupon.discountValue.toInt()}",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = discountColor
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Type tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isAvailable) PrimaryLight else SurfaceVariant
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (coupon.couponType == "PERCENTAGE") stringResource(R.string.coupon_type_percentage) else stringResource(R.string.coupon_type_fixed),
                        fontSize = 10.sp,
                        color = if (isAvailable) PrimaryDark else TextHint
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Middle: coupon info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = coupon.couponName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isAvailable) TextPrimary else TextHint,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.coupon_min_amount_format, coupon.minAmount.toInt()),
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.coupon_valid_until, coupon.expireAt.take(10)),
                    fontSize = 11.sp,
                    color = TextHint
                )
                if (isUsed) {
                    Text(
                        text = stringResource(R.string.coupon_used_at, coupon.usedAt?.take(10) ?: ""),
                        fontSize = 11.sp,
                        color = Warning
                    )
                }
            }

            // Right: action button (only for available coupons)
            if (isAvailable) {
                Button(
                    onClick = onUseClick,
                    modifier = Modifier
                        .height(32.dp)
                        .width(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.coupon_use_now),
                        fontSize = 12.sp,
                        color = TextOnPrimary
                    )
                }
            }
        }
    }
}
