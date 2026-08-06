package com.joysong.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.Order
import com.joysong.app.domain.model.ProjectWithInstitutions
import com.joysong.app.domain.repository.DiscoverRepository
import com.joysong.app.domain.repository.OrderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EntityPickerViewModel @Inject constructor(
    private val discoverRepository: DiscoverRepository,
    private val orderRepository: OrderRepository
) : ViewModel() {

    private val _doctors = MutableStateFlow<List<Doctor>>(emptyList())
    val doctors: StateFlow<List<Doctor>> = _doctors

    private val _projectsWithInstitutions = MutableStateFlow<List<ProjectWithInstitutions>>(emptyList())
    val projectsWithInstitutions: StateFlow<List<ProjectWithInstitutions>> = _projectsWithInstitutions

    private val _institutions = MutableStateFlow<List<Institution>>(emptyList())
    val institutions: StateFlow<List<Institution>> = _institutions

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        // Preload default lists so the screen is not empty on first render
        loadDoctors()
        loadProjects()
        loadInstitutions()
        loadOrders()
    }

    fun loadDoctors(query: String = "") {
        viewModelScope.launch {
            _isLoading.value = true
            discoverRepository.getDoctors(query).onSuccess { _doctors.value = it }
            _isLoading.value = false
        }
    }

    fun loadProjects(query: String = "") {
        viewModelScope.launch {
            _isLoading.value = true
            discoverRepository.getProjects(query = query).onSuccess { _projectsWithInstitutions.value = it }
            _isLoading.value = false
        }
    }

    fun loadInstitutionProjectDoctors(institutionProjectId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            discoverRepository.getInstitutionProjectDoctors(institutionProjectId)
                .onSuccess { _doctors.value = it }
            _isLoading.value = false
        }
    }

    fun loadInstitutions(query: String = "") {
        viewModelScope.launch {
            _isLoading.value = true
            discoverRepository.getInstitutions(query).onSuccess { _institutions.value = it }
            _isLoading.value = false
        }
    }

    fun loadOrders() {
        viewModelScope.launch {
            _isLoading.value = true
            orderRepository.getOrders().onSuccess { _orders.value = it }
            _isLoading.value = false
        }
    }
}
