package com.devsparkles.media3sample.di

import android.content.Context
import android.os.Build
import android.util.Log
import com.devsparkles.media3sample.core.data.ads.AdRepositoryImpl
import com.devsparkles.media3sample.core.data.ads.HttpAdTracker
import com.devsparkles.media3sample.core.data.content.FakeContentRepository
import com.devsparkles.media3sample.core.data.network.UrlConnectionHttpClient
import com.devsparkles.media3sample.core.domain.repository.AdTracker
import com.devsparkles.media3sample.core.domain.usecase.GetCatalogUseCase
import com.devsparkles.media3sample.core.domain.usecase.GetPlayableContentUseCase
import com.devsparkles.media3sample.core.domain.usecase.LoadAdScheduleUseCase
import com.devsparkles.media3sample.player.engine.PlayerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * RÔLE : "Composition root" = le SEUL endroit où l'on instancie et câble les implémentations.
 *
 * DI manuelle (recommandée par Google pour comprendre les bases) :
 * https://developer.android.com/training/dependency-injection/manual
 * En production on utiliserait Hilt (@Module/@Provides/@Singleton) ou Koin : même graphe,
 * mais généré/validé par l'outil. Savoir expliquer ce qu'Hilt fait "sous le capot" = ceci.
 *
 * C'est aussi le seul module qui dépend de :core:data : le reste de l'app ne voit que les
 * interfaces du domaine.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Scope qui vit aussi longtemps que l'application (pixels de tracking "fire and forget"). */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val userAgent = "Media3Sample/1.0 (Linux; Android ${Build.VERSION.RELEASE})"

    private val httpClient = UrlConnectionHttpClient(userAgent)

    private val adTracker: AdTracker = HttpAdTracker(httpClient, applicationScope) { Log.d("AdTracker", it) }

    private val adRepository = AdRepositoryImpl(httpClient, adTracker, logger = { Log.w("AdRepository", it) })

    private val contentRepository = FakeContentRepository()

    val getCatalog = GetCatalogUseCase(contentRepository)

    val getPlayableContent = GetPlayableContentUseCase(contentRepository)

    val playerFactory = PlayerFactory(
        context = appContext,
        loadAdSchedule = LoadAdScheduleUseCase(adRepository),
        adTracker = adTracker,
        userAgent = userAgent,
    )
}
