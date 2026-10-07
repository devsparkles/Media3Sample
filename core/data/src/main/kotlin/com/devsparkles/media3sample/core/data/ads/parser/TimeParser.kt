package com.devsparkles.media3sample.core.data.ads.parser

import com.devsparkles.media3sample.core.domain.model.AdBreakPosition

/**
 * RÔLE : convertir les formats de temps VAST/VMAP en millisecondes.
 *
 * Formats rencontrés :
 *  - "HH:MM:SS" ou "HH:MM:SS.mmm"  -> <Duration>, skipoffset, timeOffset (mid-roll)
 *  - "start" / "end"               -> timeOffset VMAP (pre-roll / post-roll)
 *  - "25%"                         -> skipoffset ou timeOffset relatif à la durée
 *  - "#2"                          -> timeOffset VMAP "position" (n-ième point de coupure),
 *                                     rare, non géré ici.
 */
object TimeParser {

    private val CLOCK = Regex("""^(\d+):(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?$""")

    /** "00:00:15.500" -> 15500. Renvoie null si le format est invalide. */
    fun parseClockMs(value: String?): Long? {
        val match = CLOCK.matchEntire(value?.trim() ?: return null) ?: return null
        val (h, m, s, ms) = match.destructured
        val millis = ms.padEnd(3, '0').toLong() // ".5" = 500 ms, pas 5 ms
        return ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1_000 + millis
    }

    /** "25%" avec une durée de 20 s -> 5000. Sinon délègue à parseClockMs. */
    fun parseOffsetMs(value: String?, durationMs: Long): Long? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.endsWith("%")) {
            val percent = trimmed.dropLast(1).toDoubleOrNull() ?: return null
            return (durationMs * percent / 100.0).toLong()
        }
        return parseClockMs(trimmed)
    }

    /** timeOffset VMAP -> position métier. Les pourcentages nécessiteraient la durée du contenu. */
    fun parseBreakPosition(timeOffset: String?): AdBreakPosition? = when (val value = timeOffset?.trim()) {
        null -> null
        "start" -> AdBreakPosition.PreRoll
        "end" -> AdBreakPosition.PostRoll
        else -> parseClockMs(value)?.let { if (it == 0L) AdBreakPosition.PreRoll else AdBreakPosition.MidRoll(it) }
    }
}
