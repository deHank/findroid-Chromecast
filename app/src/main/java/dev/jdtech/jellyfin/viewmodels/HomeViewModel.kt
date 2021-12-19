package dev.jdtech.jellyfin.viewmodels

import android.app.Application
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.R
import dev.jdtech.jellyfin.adapters.HomeItem
import dev.jdtech.jellyfin.adapters.HomeItem.Section
import dev.jdtech.jellyfin.adapters.HomeItem.ViewItem
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.unsupportedCollections
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.syncPlaybackProgress
import dev.jdtech.jellyfin.utils.toView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject internal constructor(
    private val application: Application,
    private val repository: JellyfinRepository
) : ViewModel() {
    private val uiState = MutableStateFlow<UiState>(UiState.Loading)

    sealed class UiState {
        data class Normal(val homeItems: List<HomeItem>) : UiState()
        object Loading : UiState()
        data class Error(val message: String?) : UiState()
    }

    fun onUiState(scope: LifecycleCoroutineScope, collector: (UiState) -> Unit) {
        scope.launch { uiState.collect { collector(it) } }
    }

    init {
        loadData(updateCapabilities = true)
    }

    fun refreshData() = loadData(updateCapabilities = false)

    private fun loadData(updateCapabilities: Boolean) {
        viewModelScope.launch {
            uiState.emit(UiState.Loading)
            try {
                if (updateCapabilities) repository.postCapabilities()

                val updated = loadDynamicItems() + loadViews()

                withContext(Dispatchers.Default) {
                    syncPlaybackProgress(repository)
                }
                uiState.emit(UiState.Normal(updated))
            } catch (e: Exception) {
                uiState.emit(UiState.Error(e.message))
            }
        }
    }

    private suspend fun loadDynamicItems() = withContext(Dispatchers.IO) {
        val resumeItems = repository.getResumeItems()
        val nextUpItems = repository.getNextUp()

        val items = mutableListOf<HomeSection>()
        if (resumeItems.isNotEmpty()) {
            items.add(HomeSection(application.resources.getString(R.string.continue_watching), resumeItems))
        }

        if (nextUpItems.isNotEmpty()) {
            items.add(HomeSection(application.resources.getString(R.string.next_up), nextUpItems))
        }

        items.map { Section(it) }
    }

    private suspend fun loadViews() = withContext(Dispatchers.IO) {
        repository
            .getUserViews()
            .filter { view -> unsupportedCollections().none { it.type == view.collectionType } }
            .map { view -> view to repository.getLatestMedia(view.id) }
            .filter { (_, latest) -> latest.isNotEmpty() }
            .map { (view, latest) -> view.toView().apply { items = latest } }
            .map { ViewItem(it) }
    }
}


