package com.trucdecomptable.vigieresau.net

import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Accès au shell root.
 *
 * Un seul `su` est lancé pour toute la vie de l'app. Avant, chaque commande
 * passait par un `su -c` séparé : Magisk voyait une nouvelle demande
 * d'autorisation à chaque fois et affichait donc sa notification en permanence.
 * Ici les commandes s'écrivent dans le même shell, chacune terminée par un
 * marqueur qui permet de récupérer sa sortie sans mélanger les suivantes.
 */
object Racine {

    /** Marqueur de fin de flux : le shell ne répondra plus. */
    private const val FIN = "\u0000vigie-fin\u0000"

    private val verrou = Any()
    private val compteur = AtomicLong()
    private var session: Session? = null

    /**
     * Une session ouverte sur un processus de shell. Publique pour être testable
     * avec `/bin/sh` en JVM, sans téléphone.
     */
    class Session(private val processus: Process) {

        private val ecrivain: BufferedWriter = processus.outputStream.bufferedWriter()
        private val lignes = LinkedBlockingQueue<String>()

        /** Le shell affiche parfois un message d'accueil : à jeter une fois. */
        private var amorce = true

        /** Vrai si le shell est encore en vie. */
        val vivant: Boolean get() = processus.isAlive

        init {
            Thread {
                try {
                    processus.inputStream.bufferedReader().use { lecteur: BufferedReader ->
                        while (true) {
                            val ligne = lecteur.readLine() ?: break
                            lignes.put("$ligne\n")
                        }
                    }
                } catch (e: Exception) {
                    // Flux fermé : la fin est signalée juste après.
                } finally {
                    lignes.put(FIN)
                }
            }.apply {
                isDaemon = true
                name = "vigie-racine-lecture"
                start()
            }
        }

        /**
         * Lance [commande] et rend sa sortie (erreurs comprises). Null si le
         * délai est dépassé ou si le shell ne répond plus.
         */
        fun executer(commande: String, delaiMs: Long): String? {
            if (!vivant) return null
            val marqueur = "VIGIE_FIN_" + compteur.incrementAndGet()
            return try {
                if (amorce) {
                    // Le shell peut afficher un message d'accueil : on le jette.
                    ecrire(": ; echo VIGIE_PRET:\$?")
                    if (lireJusqua("VIGIE_PRET", delaiMs) == null) return null
                    amorce = false
                }
                ecrire("( $commande ) 2>&1; echo $marqueur:\$?")
                lireJusqua(marqueur, delaiMs)
            } catch (e: Exception) {
                // Flux fermé (session déjà arrêtée) : plus rien à espérer.
                null
            }
        }

        private fun ecrire(ligne: String) {
            synchronized(ecrivain) {
                ecrivain.write(ligne + "\n")
                ecrivain.flush()
            }
        }

        /** Lit jusqu'à la ligne qui commence par [marqueur] ; null si délai ou fin. */
        private fun lireJusqua(marqueur: String, delaiMs: Long): String? {
            val sortie = StringBuilder()
            val echeance = System.currentTimeMillis() + delaiMs
            while (true) {
                val reste = echeance - System.currentTimeMillis()
                if (reste <= 0) return null
                val ligne = try {
                    lignes.poll(reste, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                } ?: return null
                if (ligne == FIN) return null
                if (ligne.startsWith("$marqueur:")) return sortie.toString()
                sortie.append(ligne)
            }
        }

        fun fermer() {
            synchronized(ecrivain) { runCatching { ecrivain.close() } }
            runCatching { processus.destroy() }
        }
    }

    /** Vrai si `su` répond et donne l'utilisateur root. */
    fun disponible(): Boolean = exec("id")?.contains("uid=0") == true

    /**
     * Lance [commande] dans le shell root et rend sa sortie. Null en cas
     * d'échec (pas de root, commande absente, délai dépassé).
     */
    fun exec(commande: String, delaiMs: Long = 8_000): String? = synchronized(verrou) {
        val courante = sessionOuNull()
        if (courante == null) {
            null
        } else {
            val resultat = courante.executer(commande, delaiMs)
            // Une commande muette laisse le shell dans un état douteux : on le
            // referme, la prochaine demande repartira proprement.
            if (resultat == null) fermer()
            resultat
        }
    }

    /** Ferme le shell root (fin de surveillance, ou session muette). */
    fun fermer() = synchronized(verrou) {
        session?.fermer()
        session = null
    }

    private fun sessionOuNull(): Session? {
        session?.let { if (it.vivant) return it else fermer() }
        return try {
            // `su` sans argument ouvre un shell root unique : c'est ce qui évite
            // une demande d'autorisation par commande.
            Session(ProcessBuilder("su").redirectErrorStream(true).start()).also { session = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Lecture d'un fichier texte lisible sans root (best effort). */
    fun lireFichier(chemin: String): String? = try {
        java.io.File(chemin).takeIf { it.canRead() }?.readText()
    } catch (e: Exception) {
        null
    }
}
