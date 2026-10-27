package com.trucdecomptable.vigieresau.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Accès au shell root. Sans root, l'app reste utilisable mais ne voit que son
 * propre trafic : tout ce qui touche aux appareils connectés (voisins, compteurs,
 * journaux DNS) passe par `su`.
 */
object Racine {

    /** Vrai si `su` répond. */
    fun disponible(): Boolean = exec("id")?.contains("uid=0") == true

    /**
     * Lance [commande] via `su -c` et rend la sortie standard. Null en cas
     * d'échec (pas de root, commande absente, délai dépassé).
     */
    fun exec(commande: String, delaiMs: Long = 8_000): String? = try {
        val processus = ProcessBuilder("su", "-c", commande).redirectErrorStream(true).start()
        val sortie = processus.inputStream.bufferedReader().use(BufferedReader::readText)
        if (!processus.waitFor(delaiMs, TimeUnit.MILLISECONDS)) {
            processus.destroy()
            null
        } else {
            sortie
        }
    } catch (e: Exception) {
        null
    }

    /** Lecture d'un fichier texte lisible sans root (best effort). */
    fun lireFichier(chemin: String): String? = try {
        java.io.File(chemin).takeIf { it.canRead() }?.readText()
    } catch (e: Exception) {
        null
    }
}
