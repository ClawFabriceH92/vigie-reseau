package com.trucdecomptable.vigieresau

import com.trucdecomptable.vigieresau.net.Racine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocole du shell root, testé sans téléphone : une session sur `/bin/sh` suit
 * le même chemin que `su` sur l'appareil (celui qui déclenchait une notification
 * Magisk par commande).
 */
class RacineSessionTest {

    private fun session(): Racine.Session =
        Racine.Session(ProcessBuilder("/bin/sh").redirectErrorStream(true).start())

    @Test
    fun `la sortie d une commande est rendue sans le marqueur`() {
        val s = session()
        try {
            assertEquals("bonjour\n", s.executer("echo bonjour", 5_000))
        } finally {
            s.fermer()
        }
    }

    @Test
    fun `plusieurs commandes se suivent dans la meme session`() {
        val s = session()
        try {
            assertEquals("un\n", s.executer("echo un", 5_000))
            assertEquals("deux\n", s.executer("echo deux", 5_000))
            assertEquals("3\n", s.executer("echo $((1+2))", 5_000))
        } finally {
            s.fermer()
        }
    }

    @Test
    fun `les erreurs sont rendues avec la sortie`() {
        val s = session()
        try {
            val sortie = s.executer("ls /dossier-inexistant-vigie", 5_000)
            assertTrue("sortie=$sortie", sortie != null && sortie.isNotBlank())
        } finally {
            s.fermer()
        }
    }

    @Test
    fun `une commande trop lente rend null au lieu de bloquer`() {
        val s = session()
        try {
            assertNull(s.executer("sleep 5", 300))
        } finally {
            s.fermer()
        }
    }

    @Test
    fun `une session fermee ne repond plus`() {
        val s = session()
        s.executer("echo pret", 5_000)
        s.fermer()
        assertNull(s.executer("echo apres", 500))
    }
}
