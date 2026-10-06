package com.devsparkles.media3sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * RÔLE : donner à chaque écran son propre ViewModelStore, comme le fait Navigation pour chaque
 * destination de la back stack.
 *
 * Pourquoi : si le PlayerViewModel était stocké au niveau de l'Activity, il ne serait détruit
 * qu'à la fin de l'Activity -> en revenant au catalogue, le player (décodeurs, session DRM)
 * resterait en mémoire. Ici :
 *  - rotation : ScreenStores (ViewModel d'Activity) survit -> le player aussi ;
 *  - retour arrière : clear(key) -> PlayerViewModel.onCleared() -> player.release().
 */
class ScreenStores : ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun ownerFor(key: String): ViewModelStoreOwner {
        val store = stores.getOrPut(key) { ViewModelStore() }
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    fun clear(key: String) {
        stores.remove(key)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}
