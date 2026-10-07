package com.devsparkles.media3sample.core.data.ads.macro

import kotlin.random.Random

/**
 * Faux générateur aléatoire pour les tests : renvoie 11111111, 22222222, 33333333...
 * Rend les tests de [CACHEBUSTING] déterministes ET prouve qu'une nouvelle valeur est tirée
 * à chaque occurrence. (Random(seed) serait déterministe aussi, mais les valeurs seraient
 * illisibles dans les assertions.)
 */
class SequenceRandom : Random() {
    private var next = 1

    override fun nextBits(bitCount: Int): Int = error("not used")

    override fun nextInt(from: Int, until: Int): Int = (next++ * 11_111_111).also { require(it in from until until) }
}
