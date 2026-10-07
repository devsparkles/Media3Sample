package com.devsparkles.media3sample.core.data.ads.macro

import com.devsparkles.media3sample.core.domain.model.AdBreakPosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * RÔLE : les stratégies concrètes, une par macro VAST supportée.
 * Ajouter une macro = écrire une classe ici + l'ajouter à MacroExpander.defaultStrategies().
 * Chaque KDoc cite la section de la spec VAST 4.1 (VAST4.1-final-Nov-8-2018.pdf).
 */

/**
 * [TIMESTAMP] — §6.2, REQUIRED, tous les pixels et requêtes VAST.
 * « The date and time at which the URI using this macro is accessed […] ISO 8601. To add
 * milliseconds, use .mmm […] before any time zone indicator. »
 * Exemple spec : 2016-01-17T8:15:07.127-05 -> ici 2026-10-07T15:00:00.123+02:00 (ISO 8601 valide).
 *
 * SimpleDateFormat plutôt que java.time : java.time n'existe qu'à partir de l'API 26 sans
 * "desugaring", et notre minSdk est 24 (le pattern "XXX" existe depuis l'API 24). En production
 * avec le desugaring activé, java.time + un Clock injecté serait l'option idiomatique.
 * SimpleDateFormat n'est PAS thread-safe : on en crée un par appel.
 */
class TimestampMacro(private val timeZone: TimeZone = TimeZone.getDefault()) : MacroStrategy {
    override val name = "TIMESTAMP"

    override fun resolve(context: MacroContext): MacroValue = MacroValue.Known(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            .apply { timeZone = this@TimestampMacro.timeZone }
            .format(Date(context.accessTimeMs)),
    )
}

/**
 * [CACHEBUSTING] — §6.2, REQUIRED. « To be replaced with a random 8-digit number ».
 * Une valeur différente par occurrence (voir MacroContext.newCacheBuster).
 */
object CacheBustingMacro : MacroStrategy {
    override val name = "CACHEBUSTING"
    override fun resolve(context: MacroContext): MacroValue = MacroValue.Known(context.newCacheBuster())
}

/**
 * [ERRORCODE] — §6.9 et §2.3.6, REQUIRED dans les pixels d'erreur. Codes du tableau §2.3.6.3
 * (100 parsing, 301 timeout wrapper, 302 trop de wrappers, 303 no ad, 403 pas de MediaFile
 * supporté, 405 lecture impossible, 900 erreur indéfinie...).
 */
object ErrorCodeMacro : MacroStrategy {
    override val name = "ERRORCODE"
    override fun resolve(context: MacroContext) = context.tracking.errorCode?.toString().toMacroValue()
}

/** [ADPLAYHEAD] — §6.8, optionnelle. Position dans la PUB, format "HH:MM:SS.mmm". */
object AdPlayheadMacro : MacroStrategy {
    override val name = "ADPLAYHEAD"
    override fun resolve(context: MacroContext) = context.tracking.adPlayheadMs?.let(::formatTimecode).toMacroValue()
}

/**
 * [MEDIAPLAYHEAD] — §6.3, optionnelle (VAST 4.1). Position dans le CONTENU dans lequel la pub
 * est insérée. Également enregistrée sous le nom [CONTENTPLAYHEAD], dépréciée en 4.1 mais
 * encore très présente dans les tags VAST 3.
 */
class MediaPlayheadMacro(override val name: String = "MEDIAPLAYHEAD") : MacroStrategy {
    override fun resolve(context: MacroContext) = context.tracking.contentPlayheadMs?.let(::formatTimecode).toMacroValue()
}

/** [BREAKPOSITION] — §6.3, optionnelle. 1 = pre-roll, 2 = mid-roll, 3 = post-roll. */
object BreakPositionMacro : MacroStrategy {
    override val name = "BREAKPOSITION"

    override fun resolve(context: MacroContext): MacroValue = when (context.tracking.breakPosition) {
        AdBreakPosition.PreRoll -> MacroValue.Known("1")
        is AdBreakPosition.MidRoll -> MacroValue.Known("2")
        AdBreakPosition.PostRoll -> MacroValue.Known("3")
        null -> MacroValue.Unknown
    }
}

/** [ASSETURI] — §6.8, optionnelle. URI du fichier pub en cours (encodée par MacroExpander). */
object AssetUriMacro : MacroStrategy {
    override val name = "ASSETURI"
    override fun resolve(context: MacroContext) = context.tracking.assetUri.toMacroValue()
}

/** Format "timecode" de la spec : HH:MM:SS.mmm (ex. 00:05:21.123). */
internal fun formatTimecode(ms: Long): String = String.format(
    Locale.US, "%02d:%02d:%02d.%03d",
    ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000,
)
