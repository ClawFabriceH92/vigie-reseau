package com.trucdecomptable.vigieresau.net

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
        // La lecture se fait dans un fil à part : une commande qui se bloque
        // (dumpsys, su en attente d'autorisation) ne doit pas figer l'app.
        val sortie = StringBuffer()
        val lecteur = Thread {
            runCatching { processus.inputStream.bufferedReader().use { sortie.append(it.readText()) } }
        }.apply { isDaemon = true; start() }
        lecteur.join(delaiMs)
        if (lecteur.isAlive) {
            processus.destroy()
            null
        } else {
            processus.waitFor(1_000, TimeUnit.MILLISECONDS)
            sortie.toString()
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
