package com.joysong.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.domain.model.Favorite
import com.joysong.app.domain.model.FavoriteType
import com.joysong.app.domain.repository.FavoriteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesUiState(
    val favorites: List<Favorite> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

sealed class FavoriteActionState {
    data object Idle : FavoriteActionState()
    data object Loading : FavoriteActionState()
    data object Success : FavoriteActionState()
    data class Error(val message: String) : FavoriteActionState()
}

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState

    private val _actionState = MutableStateFlow<FavoriteActionState>(FavoriteActionState.Idle)
    val actionState: StateFlow<FavoriteActionState> = _actionState

    init {
        loadFavorites()
    }

    fun loadFavorites() {
        viewModelScope.launch {
            _uiState.value = FavoritesUiState(isLoading = true)
            favoriteRepository.getFavorites()
                .onSuccess { _uiState.value = FavoritesUiState(favorites = it) }
                .onFailure { _uiState.value = FavoritesUiState(error = it.message) }
        }
    }

    fun addFavorite(type: FavoriteType, targetId: String) {
        viewModelScope.launch {
            _actionState.value = FavoriteActionState.Loading
            favoriteRepository.addFavorite(type, targetId)
                .onSuccess {
                    _actionState.value = FavoriteActionState.Success
                    loadFavorites()
                }
                .onFailure { _actionState.value = FavoriteActionState.Error(it.message ?: "收藏失败") }
        }
    }

    fun removeFavorite(type: FavoriteType, targetId: String) {
        viewModelScope.launch {
            _actionState.value = FavoriteActionState.Loading
            favoriteRepository.removeFavorite(type, targetId)
                .onSuccess {
                    _actionState.value = FavoriteActionState.Success
                    loadFavorites()
                }
                .onFailure { _actionState.value = FavoriteActionState.Error(it.message ?: "取消收藏失败") }
        }
    }

    fun resetActionState() {
        _actionState.value = FavoriteActionState.Idle
    }
}

