package com.joysong.app.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.ExpertArticle
import com.joysong.app.domain.model.FavoriteType
import com.joysong.app.domain.model.FilterOptions
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.ProjectWithInstitutions
import com.joysong.app.data.local.SearchHistoryManager
import com.joysong.app.domain.repository.DiscoverRepository
import com.joysong.app.domain.repository.FavoriteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoverUiState(
    val selectedTab: Int = 0,
    val selectedCategories: Set<String> = emptySet(),
    val selectedTags: Set<String> = emptySet(),
    val selectedCities: Set<String> = emptySet(),
    val searchQuery: String = "",
    val projects: List<ProjectWithInstitutions> = emptyList(),
    val diaries: List<Diary> = emptyList(),
    val doctors: List<Doctor> = emptyList(),
    val institutions: List<Institution> = emptyList(),
    val articles: List<ExpertArticle> = emptyList(),
    val filterOptions: FilterOptions = FilterOptions(),
    val searchHistory: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val discoverRepository: DiscoverRepository,
    private val favoriteRepository: FavoriteRepository,
    private val searchHistoryManager: SearchHistoryManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState

    // 文章收藏状态：独立追踪每篇文章，避免串状态
    private val _articleFavoriteStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    init {
        loadFilterOptions()
        loadData()
        viewModelScope.launch {
            searchHistoryManager.searchHistoryFlow.collect { history ->
                _uiState.value = _uiState.value.copy(searchHistory = history)
            }
        }
    }

    fun loadFilterOptions() {
        viewModelScope.launch {
            val result = discoverRepository.getFilterOptions()
            result.onSuccess { options ->
                _uiState.value = _uiState.value.copy(filterOptions = options)
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val state = _uiState.value
            val categories = state.selectedCategories.joinToString(",")
            val tags = state.selectedTags.joinToString(",")
            val cities = state.selectedCities.joinToString(",")
            val projects = discoverRepository.getProjects(categories, cities, tags, state.searchQuery)
            val diaries = discoverRepository.getDiaries(state.searchQuery)
            val doctors = discoverRepository.getDoctors(state.searchQuery)
            val institutions = discoverRepository.getInstitutions(state.searchQuery)
            val articles = discoverRepository.getArticles(state.searchQuery)

            val articleList = articles.getOrDefault(emptyList())
            // 使用 _uiState.value 而非捕获的旧 state，避免覆盖并发的 selectTab 等修改
            _uiState.value = _uiState.value.copy(
                projects = projects.getOrDefault(emptyList()),
                diaries = diaries.getOrDefault(emptyList()),
                doctors = doctors.getOrDefault(emptyList()),
                institutions = institutions.getOrDefault(emptyList()),
                articles = articleList,
                isLoading = false,
                error = projects.exceptionOrNull()?.message
            )

            // 异步加载每篇文章的收藏状态（通过 API）
            articleList.forEach { article ->
                viewModelScope.launch {
                    favoriteRepository.isFavorite(FavoriteType.ARTICLE, article.id)
                        .onSuccess { status ->
                            _articleFavoriteStates.value =
                                _articleFavoriteStates.value + (article.id to status.favorited)
                            applyArticleFavorites()
                        }
                }
            }
        }
    }

    /**
     * 将 _articleFavoriteStates 中的收藏状态映射到 uiState.articles
     */
    private fun applyArticleFavorites() {
        val favMap = _articleFavoriteStates.value
        _uiState.value = _uiState.value.copy(
            articles = _uiState.value.articles.map { article ->
                article.copy(isFavorited = favMap[article.id] ?: false)
            }
        )
    }

    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = index)
    }

    fun applyFilters(categories: Set<String>, tags: Set<String>, cities: Set<String>) {
        _uiState.value = _uiState.value.copy(
            selectedCategories = categories,
            selectedTags = tags,
            selectedCities = cities
        )
        loadData()
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun search() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isNotEmpty()) {
            viewModelScope.launch { searchHistoryManager.addSearchQuery(query) }
        }
        loadData()
    }

    fun removeHistoryItem(query: String) {
        viewModelScope.launch { searchHistoryManager.removeSearchQuery(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { searchHistoryManager.clearSearchHistory() }
    }

    fun toggleFavorite(articleId: String) {
        val currentMap = _articleFavoriteStates.value
        val wasFavorited = currentMap[articleId] ?: false
        // 乐观更新
        _articleFavoriteStates.value = currentMap + (articleId to !wasFavorited)
        applyArticleFavorites()

        viewModelScope.launch {
            val article = _uiState.value.articles.find { it.id == articleId }
            val result = if (wasFavorited) {
                favoriteRepository.removeFavorite(FavoriteType.ARTICLE, articleId)
            } else {
                favoriteRepository.addFavorite(
                    FavoriteType.ARTICLE,
                    articleId,
                    article?.title ?: "",
                    article?.coverImage ?: ""
                )
            }
            result.onFailure {
                // 回滚
                _articleFavoriteStates.value = currentMap
                applyArticleFavorites()
            }
        }
    }
}
