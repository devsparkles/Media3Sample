package com.devsparkles.media3sample.core.data.ads.macro

import com.devsparkles.media3sample.core.domain.model.TrackingContext
import java.net.URLEncoder
import kotlin.random.Random

/**
 * RÔLE : le "contexte" du pattern Strategy. Il repère les macros d'une URL en UN SEUL passage
 * et délègue le calcul de chaque valeur à la stratégie correspondante.
 *
 * À appeler juste avant la requête HTTP (pixel de tracking OU requête VAST/Wrapper : spec §6.1
 * « Macro Replacement Responsibility » : c'est la partie qui fait la requête qui remplace).
 *
 * Règles (VAST 4.1 §6.1) :
 *  - macro implémentée avec valeur -> valeur encodée avec encodeURIComponent
 *    (« make sure to apply encodeURIComponent to any value »)
 *  - valeur inconnue -> "-1" ; valeur non partageable (politique, consentement) -> "-2"
 *  - macro de la spec NON implémentée ici -> "-1"
 *  - macro hors spec (ex : [AD_MT] de Google, placeholders propriétaires) -> laissée INTACTE.
 *    Implementation Note de la spec : « do not replace all unknown macros with -1, only do
 *    this for macros specifically mentioned in this section that you decide not to implement ».
 *  - extension (hors spec, par robustesse) : %5BMACRO%5D est reconnu comme [MACRO], car
 *    certains serveurs ré-encodent les URLs.
 *
 * Un seul passage de regex : une valeur insérée n'est jamais re-scannée (une ASSETURI contenant
 * "[TIMESTAMP]" ne sera pas remplacée une deuxième fois), et l'ordre des stratégies n'a pas
 * d'importance.
 *
 * @param clock source du temps, injectée : en test on fige l'heure.
 * @param random source d'aléatoire, injectée : en test on rend les valeurs prévisibles.
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

    fun expand(url: String, tracking: TrackingContext = TrackingContext()): String {
        // Une heure d'accès PAR URI (spec §6.2), lue une fois : deux [TIMESTAMP] de la même URL
        // sont identiques, deux URLs envoyées à des moments différents ont chacune la leur.
        val context = MacroContext(tracking, accessTimeMs = clock(), random = random)
        return MACRO_PATTERN.replace(url) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }.uppercase()
            val value = strategiesByName[name]?.resolve(context)
                ?: if (name in VAST_41_MACROS) MacroValue.Unknown else return@replace match.value
            when (value) {
                is MacroValue.Known -> encodeUriComponent(value.raw)
                MacroValue.Unknown -> UNKNOWN_VALUE
                MacroValue.Restricted -> RESTRICTED_VALUE
            }
        }
    }

    companion object {
        const val UNKNOWN_VALUE = "-1"
        const val RESTRICTED_VALUE = "-2"

        /** [MACRO] ou %5BMACRO%5D (insensible à la casse pour %5b/%5d). */
        private val MACRO_PATTERN = Regex("""\[([A-Za-z0-9_]+)]|%5[Bb]([A-Za-z0-9_]+)%5[Dd]""")

        /** Toutes les macros définies en section 6 de VAST 4.1. */
        val VAST_41_MACROS: Set<String> = setOf(
            "TIMESTAMP", "CACHEBUSTING", "CONTENTPLAYHEAD", "MEDIAPLAYHEAD", "BREAKPOSITION",
            "BLOCKEDADCATEGORIES", "ADCATEGORIES", "ADCOUNT", "TRANSACTIONID", "PLACEMENTTYPE",
            "ADTYPE", "IFA", "IFATYPE", "CLIENTUA", "SERVERUA", "DEVICEUA", "SERVERSIDE", "DEVICEIP",
            "LATLONG", "DOMAIN", "PAGEURL", "VASTVERSIONS", "APIFRAMEWORKS", "EXTENSIONS",
            "VERIFICATIONVENDORS", "OMIDPARTNER", "MEDIAMIME", "PLAYERCAPABILITIES", "CLICKTYPE",
            "PLAYERSTATE", "INVENTORYSTATE", "PLAYERSIZE", "ADPLAYHEAD", "ASSETURI", "CONTENTID",
            "CONTENTURI", "PODSEQUENCE", "ADSERVINGID", "CLICKPOS", "ERRORCODE", "REASON",
            "LIMITADTRACKING", "REGULATIONS", "GDPRCONSENT",
        )

        fun defaultStrategies(): List<MacroStrategy> = listOf(
            TimestampMacro(),
            CacheBustingMacro,
            ErrorCodeMacro,
            AdPlayheadMacro,
            MediaPlayheadMacro(),
            MediaPlayheadMacro(name = "CONTENTPLAYHEAD"),
            BreakPositionMacro,
            AssetUriMacro,
        )

        /**
         * Équivalent du encodeURIComponent JavaScript exigé par la spec. URLEncoder de Java vise
         * les formulaires HTML : il code l'espace en "+" et encode ! ' ( ) ~, que
         * encodeURIComponent laisse tels quels. On corrige ces écarts.
         */
        internal fun encodeUriComponent(value: String): String = URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")
    }
}
