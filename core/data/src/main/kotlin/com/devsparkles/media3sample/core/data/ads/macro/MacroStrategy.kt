package com.devsparkles.media3sample.core.data.ads.macro

import com.devsparkles.media3sample.core.domain.model.TrackingContext
import kotlin.random.Random

/**
 * RÔLE : contrat du design pattern STRATEGY appliqué aux macros VAST.
 *
 * Une macro = un placeholder entre crochets dans une URL, que le player doit remplacer avant
 * d'envoyer la requête : https://track.com/imp?t=[TIMESTAMP]&cb=[CACHEBUSTING]
 * Référence : VAST 4.1, section 6 « Macros » (p. 91-108)
 * https://iabtechlab.com/standards/vast/
 *
 * Pourquoi Strategy plutôt qu'une suite de `replace()` ou un gros `when` ?
 *  - Ouvert/fermé (SOLID) : ajouter une macro = ajouter une classe, sans toucher au parser.
 *  - Testabilité : chaque stratégie se teste seule, avec un contexte construit à la main.
 * Pattern : https://refactoring.guru/fr/design-patterns/strategy
 *
 * Répartition des responsabilités :
 *  - la STRATÉGIE calcule une valeur brute (elle ne connaît pas l'URL) ;
 *  - MacroExpander trouve les macros, encode les valeurs et applique les règles -1 / -2.
 */
interface MacroStrategy {

    /** Nom de la macro SANS crochets, en majuscules : "TIMESTAMP". */
    val name: String

    fun resolve(context: MacroContext): MacroValue
}

/**
 * Résultat à 3 états, imposé par la spec VAST 4.1 §6.1 « Marking Macro Values as Unknown or
 * Unavailable » : une macro optionnelle non fournie se remplace par -1 (inconnue) ou -2
 * (connue mais non partageable, ex. refus de consentement). Un simple `String?` ne suffit pas
 * à distinguer les deux.
 */
sealed interface MacroValue {
    /** Valeur brute, NON encodée (l'encodage est fait par MacroExpander). */
    data class Known(val raw: String) : MacroValue

    /** -1 : « Value is unknown, but would be shared if it was known ». */
    data object Unknown : MacroValue

    /** -2 : « Value is known, but information can't be shared because of policy ». */
    data object Restricted : MacroValue
}

internal fun String?.toMacroValue(): MacroValue = if (this == null) MacroValue.Unknown else MacroValue.Known(this)

/**
 * Contexte d'UNE requête HTTP, créé par MacroExpander pour chaque URL au moment de l'envoi.
 *
 * @property accessTimeMs heure de la requête. Spec §6.2 : [TIMESTAMP] = « the date and time at
 *           which the URI using this macro is accessed » -> une heure PAR URI, prise à l'envoi.
 * @property tracking données de l'événement fournies par le player (erreur, positions...).
 */
class MacroContext(
    val tracking: TrackingContext,
    val accessTimeMs: Long,
    private val random: Random,
) {
    /**
     * Un nouveau nombre à 8 chiffres à CHAQUE appel. Chaque occurrence de [CACHEBUSTING] a donc
     * sa propre valeur, ce qui garantit que deux URLs identiques (même tracker présent à deux
     * niveaux de Wrapper) produisent deux requêtes distinctes qu'aucun cache ne fusionnera.
     */
    fun newCacheBuster(): String = random.nextInt(10_000_000, 100_000_000).toString()
}
