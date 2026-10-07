package com.devsparkles.media3sample.core.data.ads.macro

import com.devsparkles.media3sample.core.domain.model.TrackingContext

/**
 * RÔLE : contrat du design pattern STRATEGY appliqué aux macros VAST.
 *
 * Une macro = un placeholder entre crochets dans une URL de tracking, que le player doit
 * remplacer avant d'envoyer la requête : https://track.com/imp?t=[TIMESTAMP]&cb=[CACHEBUSTING]
 * Liste officielle (VAST 4.x) :
 * https://interactiveadvertisingbureau.github.io/vast/vast4macros/vast4-macros-latest.html
 *
 * Pourquoi Strategy plutôt qu'une suite de `replace()` ou un gros `when` ?
 *  - Ouvert/fermé (SOLID) : ajouter une macro = ajouter une classe, sans toucher au parser.
 *  - Testabilité : chaque stratégie se teste seule, avec un contexte construit à la main.
 *  - Chaque stratégie peut avoir ses propres dépendances (fuseau horaire, format...).
 * Pattern : https://refactoring.guru/fr/design-patterns/strategy
 */
interface MacroStrategy {

    /** Nom de la macro SANS crochets, en majuscules : "TIMESTAMP". */
    val name: String

    /**
     * Valeur BRUTE (non encodée pour l'URL : c'est MacroExpander qui encode).
     * @return null si la valeur n'est pas connue dans ce contexte.
     */
    fun resolve(context: MacroContext): String?
}

/**
 * Instantané des valeurs, figé UNE SEULE FOIS par événement de tracking.
 *
 * C'est la correction du bug classique « chaque macro régénère son propre temps » :
 * si [TIMESTAMP] apparaît deux fois, ou dans les 3 URLs d'une même impression (une par
 * niveau de Wrapper), toutes doivent porter la MÊME valeur, sinon la régie ne peut pas
 * rapprocher les hits. Les stratégies sont donc des fonctions PURES de ce contexte :
 * elles ne lisent jamais l'horloge elles-mêmes.
 */
data class MacroContext(
    val timestampMs: Long,
    val cacheBuster: String,
    val tracking: TrackingContext,
)
