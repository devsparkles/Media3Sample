package com.devsparkles.media3sample.feature.player.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.devsparkles.media3sample.core.domain.model.VideoContent
import com.devsparkles.media3sample.core.domain.usecase.GetCatalogUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * RÔLE : écran de sélection des contenus de démo (DRM, pubs, cas d'erreur).
 */
class CatalogViewModel(getCatalog: GetCatalogUseCase) : ViewModel() {
    // stateIn(WhileSubscribed(5000)) : le flow reste actif 5 s après la dernière collecte
    // (survit à une rotation sans recharger). Pattern recommandé par Google.
    val catalog: StateFlow<List<VideoContent>> = flow { emit(getCatalog()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun factory(getCatalog: GetCatalogUseCase): ViewModelProvider.Factory = viewModelFactory {
            initializer { CatalogViewModel(getCatalog) }
        }
    }
}

@Composable
fun CatalogScreen(viewModel: CatalogViewModel, onContentClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val items by viewModel.catalog.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxSize()) {
        items(items, key = { it.id }) { content ->
            ListItem(
                headlineContent = { Text(content.title) },
                supportingContent = {
                    val tags = listOfNotNull(
                        content.streamType.name,
                        content.drm?.scheme?.name,
                        if (content.adTagUrl != null) "PUB" else null,
                    )
                    Text(tags.joinToString(" · "))
                },
                modifier = Modifier.clickable { onContentClick(content.id) },
            )
            HorizontalDivider()
        }
    }
}
