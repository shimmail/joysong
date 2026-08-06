package com.joysong.app.ui.booking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.Order
import com.joysong.app.data.repository.ConsultationFeeRepository
import com.joysong.app.domain.repository.DiscoverRepository
import com.joysong.app.domain.repository.InstitutionProjectDetailInfo
import com.joysong.app.domain.repository.OrderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookingConfirmUiState(
    val detail: InstitutionProjectDetailInfo? = null,
    val doctors: List<Doctor> = emptyList(),
    val selectedDoctor: Doctor? = null,
    val remark: String = "",
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val error: String? = null,
    // Coupon
    val selectedCouponId: Long? = null,
    val selectedCouponName: String? = null,
    val selectedCouponDiscount: Double = 0.0,
    // Appointment time
    val appointmentTime: String = "",
    // Consultation fee from backend
    val consultationFee: Double = 0.0,
    // Order created
    val createdOrder: Order? = null,
    val orderCreated: Boolean = false
)

@HiltViewModel
class BookingConfirmViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val discoverRepository: DiscoverRepository,
    private val orderRepository: OrderRepository,
    private val consultationFeeRepository: ConsultationFeeRepository
) : ViewModel() {

    val institutionProjectId: String = savedStateHandle.get<String>("institutionProjectId") ?: ""
    val projectId: String = savedStateHandle.get<String>("projectId") ?: ""
    val institutionId: String = savedStateHandle.get<String>("institutionId") ?: ""

    private val _uiState = MutableStateFlow(BookingConfirmUiState())
    val uiState: StateFlow<BookingConfirmUiState> = _uiState.asStateFlow()

    private val savedStateHandle = savedStateHandle

    init {
        loadData()
        observeSelectedCoupon()
    }

    private fun loadData() {
        if (institutionId.isBlank() || projectId.isBlank()) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "参数无效")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // Load institution project detail
                val detailResult = discoverRepository.getInstitutionProjectDetail(institutionId, projectId)
                detailResult.onSuccess { detail ->
                    _uiState.value = _uiState.value.copy(detail = detail)
                    // Load doctors for this institution project
                    val ipId = detail.institutionProject.id
                    discoverRepository.getInstitutionProjectDoctors(ipId)
                        .onSuccess { doctors ->
                            _uiState.value = _uiState.value.copy(
                                doctors = doctors,
                                isLoading = false
                            )
                        }
                        .onFailure {
                            _uiState.value = _uiState.value.copy(
                                doctors = emptyList(),
                                isLoading = false
                            )
                        }
                }
                detailResult.onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = it.message ?: "加载失败"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "加载失败"
                )
            }
        }
    }

    private fun observeSelectedCoupon() {
        viewModelScope.launch {
            savedStateHandle.getStateFlow<String?>("selectedCoupon", null).collect { json ->
                if (json != null) {
                    try {
                        val obj = org.json.JSONObject(json)
                        _uiState.value = _uiState.value.copy(
                            selectedCouponId = obj.getLong("id"),
                            selectedCouponName = obj.getString("couponName"),
                            selectedCouponDiscount = obj.getDouble("discountValue")
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun clearCoupon() {
        _uiState.value = _uiState.value.copy(
            selectedCouponId = null,
            selectedCouponName = null,
            selectedCouponDiscount = 0.0
        )
        savedStateHandle.set("selectedCoupon", null)
    }

    fun selectDoctor(doctor: Doctor) {
        _uiState.value = _uiState.value.copy(selectedDoctor = doctor, consultationFee = 0.0)
        fetchConsultationFee(doctor.id)
    }

    private fun fetchConsultationFee(doctorId: String) {
        if (institutionProjectId.isBlank()) return
        viewModelScope.launch {
            consultationFeeRepository.getConsultationFee(doctorId, institutionProjectId)
                .onSuccess { fee ->
                    _uiState.value = _uiState.value.copy(consultationFee = fee)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(consultationFee = 0.0)
                }
        }
    }

    fun updateRemark(remark: String) {
        _uiState.value = _uiState.value.copy(remark = remark)
    }

    fun onAppointmentTimeSelected(time: String) {
        _uiState.value = _uiState.value.copy(appointmentTime = time)
    }

    fun confirmBooking() {
        val state = _uiState.value
        val doctor = state.selectedDoctor ?: run {
            _uiState.value = state.copy(error = "请选择医生")
            return
        }
        if (state.appointmentTime.isBlank()) {
            _uiState.value = state.copy(error = "请选择预约时间")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true, error = null)
            orderRepository.createOrder(
                projectId = projectId,
                institutionProjectId = institutionProjectId,
                doctorId = doctor.id,
                quantity = 1,
                remark = state.remark,
                userCouponId = state.selectedCouponId,
                appointmentTime = state.appointmentTime.takeIf { it.isNotBlank() }
            )
                .onSuccess { order ->
                    _uiState.value = _uiState.value.copy(
                        isCreating = false,
                        createdOrder = order,
                        orderCreated = true
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isCreating = false,
                        error = it.message ?: "创建订单失败"
                    )
                }
        }
    }

    fun consumeOrderCreated() {
        _uiState.value = _uiState.value.copy(orderCreated = false)
    }

    /** Consultation fee from backend config, default 0 */
    fun getConsultationFee(): Double = _uiState.value.consultationFee
}
