package com.trucdecomptable.vigieresau.update

/**
 * Cadence des contrôles. Fonctions pures, testées en JVM.
 *
 * Un contrôle qui n'a pas atteint GitHub ne garde pas le créneau horaire :
 * l'appelant remet l'horodatage à zéro en cas d'échec.
 */
internal object UpdateSchedule {

    /** Plancher entre deux contrôles automatiques. */
    const val MIN_INTERVAL_MS = 60 * 60_000L

    /** Période de la boucle tant que l'app est ouverte (le plancher reste appliqué). */
    const val BOUCLE_MS = 30 * 60_000L

    const val NEVER = 0L

    fun isDue(manual: Boolean, lastCheckAt: Long, now: Long): Boolean =
        manual || lastCheckAt == NEVER || now - lastCheckAt >= MIN_INTERVAL_MS
}
