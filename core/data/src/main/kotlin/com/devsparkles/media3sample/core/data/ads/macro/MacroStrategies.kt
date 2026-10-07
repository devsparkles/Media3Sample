package com.devsparkles.media3sample.core.data.ads.macro

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * RÔLE : les stratégies concrètes, une par macro VAST supportée.
 * Ajouter une macro = écrire une classe ici + l'ajouter à MacroExpander.defaultStrategies().
 */

/**
 * [TIMESTAMP] : date et heure de l'événement, ISO 8601 avec millisecondes et fuseau.
 * Exemple : 2026-10-07T15:00:00.000+02:00
 *
 * SimpleDateFormat plutôt que java.time : java.time n'existe qu'à partir d'Android 8 (API 26)
 * sans "desugaring", et notre minSdk est 24. Le pattern "XXX" (fuseau ISO) existe depuis l'API 24.
 * SimpleDateFormat n'est PAS thread-safe : on en crée un par appel (les pixels partent depuis
 * plusieurs threads du pool IO).
 * Le fuseau est injecté : en test on le fixe, sinon le test dépendrait de la machine.
 */
class TimestampMacro(private val timeZone: TimeZone = TimeZone.getDefault()) : MacroStrategy {
    override val name = "TIMESTAMP"

    override fun resolve(context: MacroContext): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            .apply { timeZone = this@TimestampMacro.timeZone }
            .format(Date(context.timestampMs))
}

/**
 * [CACHEBUSTING] : nombre aléatoire à 8 chiffres. Il empêche les caches et proxies HTTP de
 * dédoublonner deux pixels identiques (deux vraies impressions compteraient pour une).
 * La valeur est tirée dans l'instantané (MacroContext), pas ici : la stratégie reste pure et testable.
 */
object CacheBustingMacro : MacroStrategy {
    override val name = "CACHEBUSTING"
    override fun resolve(context: MacroContext): String = context.cacheBuster
}

/** [ERRORCODE] : code d'erreur VAST (100 parsing, 303 no ad, 405 média illisible...). */
object ErrorCodeMacro : MacroStrategy {
    override val name = "ERRORCODE"
    override fun resolve(context: MacroContext): String? = context.tracking.errorCode?.toString()
}

/** [ADPLAYHEAD] : position dans la pub au format HH:MM:SS.mmm (ex : 00:00:07.500). */
object AdPlayheadMacro : MacroStrategy {
    override val name = "ADPLAYHEAD"

    override fun resolve(context: MacroContext): String? =
        context.tracking.adPlayheadMs?.let { ms ->
            String.format(
                Locale.US, "%02d:%02d:%02d.%03d",
                ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000,
            )
        }
}

/** [ASSETURI] : URL du fichier vidéo de la pub (encodée pour l'URL par MacroExpander). */
object AssetUriMacro : MacroStrategy {
    override val name = "ASSETURI"
    override fun resolve(context: MacroContext): String? = context.tracking.assetUri
}
