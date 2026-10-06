package com.devsparkles.media3sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devsparkles.media3sample.feature.player.PlayerScreen
import com.devsparkles.media3sample.feature.player.PlayerViewModel
import com.devsparkles.media3sample.feature.player.catalog.CatalogScreen
import com.devsparkles.media3sample.feature.player.catalog.CatalogViewModel
import com.devsparkles.media3sample.ui.theme.Media3SampleTheme

/**
 * RÔLE : point d'entrée UI. Navigation minimaliste à 2 écrans (catalogue -> player) via un
 * simple état ; dans une vraie app : Navigation Compose / Navigation 3.
 */
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MediaSampleApplication).container

        setContent {
            Media3SampleTheme {
                // rememberSaveable : survit à la rotation ET à la mort du process.
                var selectedContentId by rememberSaveable { mutableStateOf<String?>(null) }
                val contentId = selectedContentId

                if (contentId == null) {
                    Scaffold(topBar = { TopAppBar(title = { Text("Media3 Player Sample") }) }) { padding ->
                        CatalogScreen(
                            viewModel = viewModel(factory = CatalogViewModel.factory(container.getCatalog)),
                            onContentClick = { selectedContentId = it },
                            modifier = Modifier.padding(padding),
                        )
                    }
                } else {
                    val screenStores: ScreenStores = viewModel()
                    val owner = remember(contentId) { screenStores.ownerFor(contentId) }
                    BackHandler {
                        screenStores.clear(contentId) // -> onCleared() -> player.release()
                        selectedContentId = null
                    }
                    PlayerScreen(
                        viewModel = viewModel(
                            viewModelStoreOwner = owner,
                            factory = PlayerViewModel.factory(contentId, container.getPlayableContent, container.playerFactory),
                        ),
                    )
                }
            }
        }
    }
}
