package com.devsparkles.media3sample.core.data.ads.macro

import com.devsparkles.media3sample.core.domain.model.TrackingContext
import java.net.URLEncoder
import kotlin.random.Random

/**
 * RÔLE : le "contexte" du pattern Strategy. Il repère les macros dans une URL et délègue
 * le calcul de chaque valeur à la stratégie correspondante.
 *
 * Utilisation en 2 temps (c'est ce qui garantit la cohérence des valeurs) :
 *   val snapshot = expander.snapshot(trackingContext)          // 1 fois par événement
 *   urls.map { expander.expand(it, snapshot) }                // N URLs, mêmes valeurs
 *
 * Règles appliquées :
 *  - macro connue avec valeur          -> valeur encodée pour l'URL
 *  - macro connue SANS valeur          -> "-1" (convention VAST 4.1 : « valeur inconnue »)
 *  - macro inconnue de nos stratégies  -> laissée INTACTE (certaines régies utilisent leurs
 *    propres placeholders, ex : [AD_MT] chez Google, qu'on ne doit pas casser)
 *  - les crochets encodés %5BMACRO%5D sont aussi reconnus (URLs déjà encodées par un serveur).
 *
 * @param clock source du temps, injectée : en test on fige l'heure.
 * @param random source d'aléatoire, injectée : en test on utilise une graine fixe.
 */
class MacroExpander(
    strategies: List<MacroStrategy> = defaultStrategies(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val strategiesByName: Map<String, MacroStrategy> = strategies.associateBy { it.name }

    init {
        require(strategiesByName.size == strategies.size) { "Duplicate macro strategy names" }
    }

    /** Fige l'heure et le cache-buster AU MOMENT DE L'ÉVÉNEMENT (pas au moment de l'envoi). */
    fun snapshot(tracking: TrackingContext): MacroContext = MacroContext(
        timestampMs = clock(),
        cacheBuster = random.nextInt(10_000_000, 100_000_000).toString(), // toujours 8 chiffres
        tracking = tracking,
    )

    fun expand(url: String, context: MacroContext): String =
        MACRO_PATTERN.replace(url) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }.uppercase()
            val strategy = strategiesByName[name] ?: return@replace match.value
            encode(strategy.resolve(context) ?: UNKNOWN_VALUE)
        }

    /**
     * URLEncoder encode pour les formulaires HTML (espace -> "+"). Dans une URL, l'espace doit
     * être "%20", et un "+" littéral (fuseau "+02:00") devient "%2B" : on corrige l'espace.
     */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        const val UNKNOWN_VALUE = "-1"

        /** [MACRO] ou %5BMACRO%5D (insensible à la casse pour %5b/%5d). */
        private val MACRO_PATTERN = Regex("""\[([A-Za-z0-9_]+)]|%5[Bb]([A-Za-z0-9_]+)%5[Dd]""")

        fun defaultStrategies(): List<MacroStrategy> = listOf(
            TimestampMacro(),
            CacheBustingMacro,
            ErrorCodeMacro,
            AdPlayheadMacro,
            AssetUriMacro,
        )
    }
}
