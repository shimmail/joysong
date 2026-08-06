package com.joysong.app.ui.coupon

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.domain.model.UserCoupon
import com.joysong.app.domain.repository.CouponRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CouponTab {
    ALL, AVAILABLE, USED, EXPIRED
}

data class CouponListUiState(
    val isLoading: Boolean = true,
    val coupons: List<UserCoupon> = emptyList(),
    val error: String? = null,
    val selectedTab: CouponTab = CouponTab.ALL
)

@HiltViewModel
class CouponListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val couponRepository: CouponRepository
) : ViewModel() {

    val originalPrice: Double = savedStateHandle.get<String>("originalPrice")?.toDoubleOrNull() ?: 0.0

    private val _uiState = MutableStateFlow(CouponListUiState())
    val uiState: StateFlow<CouponListUiState> = _uiState

    init {
        loadCoupons()
    }

    fun loadCoupons() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            couponRepository.getMyCoupons()
                .onSuccess { coupons ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        coupons = coupons
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = it.message ?: "加载失败"
                    )
                }
        }
    }

    fun selectTab(tab: CouponTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun getFilteredCoupons(): List<UserCoupon> {
        return when (_uiState.value.selectedTab) {
            CouponTab.ALL -> _uiState.value.coupons
            CouponTab.AVAILABLE -> _uiState.value.coupons.filter { it.status == "UNUSED" }
            CouponTab.USED -> _uiState.value.coupons.filter { it.status == "USED" }
            CouponTab.EXPIRED -> _uiState.value.coupons.filter { it.status == "EXPIRED" }
        }
    }

    fun isCouponUsable(coupon: UserCoupon): Boolean {
        if (coupon.status != "UNUSED") return false
        if (originalPrice > 0 && coupon.minAmount > originalPrice) return false
        return true
    }
}
