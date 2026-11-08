package com.trucdecomptable.vigieresau

import com.trucdecomptable.vigieresau.data.RequeteDns
import com.trucdecomptable.vigieresau.data.etiquette
import com.trucdecomptable.vigieresau.data.regrouperParAppareil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regroupement des requêtes DNS par appareil : la base de l'onglet Requêtes. */
class RegroupementTest {

    private fun r(ip: String, domaine: String, quand: Long) = RequeteDns(quand, ip, domaine)

    private val requetes = listOf(
        r("192.168.43.5", "tiktok.com", 1_000),
        r("192.168.43.5", "tiktok.com", 2_000),
        r("192.168.43.5", "google.com", 3_000),
        r("192.168.43.7", "netflix.com", 4_000),
    )

    @Test
    fun `un groupe par appareil avec les domaines les plus demandes en premier`() {
        val groupes = regrouperParAppareil(requetes)
        assertEquals(listOf("192.168.43.7", "192.168.43.5"), groupes.map { it.ip })
        val premier = groupes.first { it.ip == "192.168.43.5" }
        assertEquals(3, premier.total)
        assertEquals(listOf("tiktok.com", "google.com"), premier.domaines.map { it.domaine })
        assertEquals(2, premier.domaines.first().occurrences)
        assertEquals(2_000L, premier.domaines.first().dernier)
        assertEquals(3_000L, premier.dernier)
    }

    @Test
    fun `le filtre ne garde que les domaines correspondants`() {
        val tiktok = regrouperParAppareil(requetes, "TIKTOK")
        assertEquals(1, tiktok.size)
        assertEquals("192.168.43.5", tiktok.first().ip)
        assertEquals(2, tiktok.first().total)
        assertEquals(listOf("tiktok.com"), tiktok.first().domaines.map { it.domaine })

        // Un appareil dont plus aucun domaine ne correspond disparaît.
        assertTrue(regrouperParAppareil(requetes, "netflix").none { it.ip == "192.168.43.5" })

        // Filtre vide ou espaces : tout revient.
        assertEquals(2, regrouperParAppareil(requetes, "   ").size)
        assertEquals(2, regrouperParAppareil(requetes).size)
    }

    @Test
    fun `le nom de l appareil est affiche a cote de l IP`() {
        // Nom connu : le nom en titre, l'IP en dessous.
        assertEquals("DESKTOP-4F2" to "192.168.43.5", etiquette("DESKTOP-4F2", "192.168.43.5"))
        // Pas de nom : l'IP sert de titre, sans la répéter.
        assertEquals("192.168.43.5" to "", etiquette(null, "192.168.43.5"))
        assertEquals("192.168.43.5" to "", etiquette("   ", "192.168.43.5"))
        // Le libellé vaut déjà l'IP : pas de doublon.
        assertEquals("192.168.43.5" to "", etiquette("192.168.43.5", "192.168.43.5"))
        // Une MAC comme libellé reste utile au-dessus de l'IP.
        assertEquals("aa:bb:cc:dd:ee:ff" to "192.168.43.5", etiquette(" aa:bb:cc:dd:ee:ff ", "192.168.43.5"))
    }

    @Test
    fun `aucune requete donne aucune groupe`() {
        assertTrue(regrouperParAppareil(emptyList()).isEmpty())
        assertTrue(regrouperParAppareil(emptyList(), "tiktok").isEmpty())
    }
}
