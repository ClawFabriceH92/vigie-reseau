package com.trucdecomptable.vigieresau

import com.trucdecomptable.vigieresau.net.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsTest {

    /** Construit une requête DNS minimale : en-tête + un nom + type A + classe IN. */
    private fun requete(nom: String, reponse: Boolean = false): ByteArray {
        val sortie = mutableListOf<Byte>()
        val fanions = if (reponse) 0x8180 else 0x0100
        sortie.addAll(listOf(0x12, 0x34, (fanions shr 8).toByte(), (fanions and 0xFF).toByte()))
        sortie.addAll(listOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        nom.split('.').forEach { morceau ->
            sortie.add(morceau.length.toByte())
            morceau.forEach { sortie.add(it.code.toByte()) }
        }
        sortie.add(0)
        sortie.addAll(listOf(0x00, 0x01, 0x00, 0x01))
        return sortie.toByteArray()
    }

    @Test
    fun `lit le domaine d une question`() {
        assertEquals("tiktok.com", Dns.nomDemande(requete("tiktok.com")))
        assertEquals("www.impots.gouv.fr", Dns.nomDemande(requete("www.impots.gouv.fr")))
    }

    @Test
    fun `ignore une reponse`() {
        assertNull(Dns.nomDemande(requete("tiktok.com", reponse = true)))
    }

    @Test
    fun `trame vide ou tronquee ne plante pas`() {
        assertNull(Dns.nomDemande(ByteArray(0)))
        assertNull(Dns.nomDemande(byteArrayOf(0x12, 0x34, 0x01, 0x00)))
        // En-tête complet mais nom tronqué en plein milieu.
        val tronquee = requete("exemple.fr").copyOfRange(0, 15)
        assertNull(Dns.nomDemande(tronquee))
    }

    @Test
    fun `pointeur de compression coupe proprement`() {
        val sortie = mutableListOf<Byte>()
        sortie.addAll(listOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        sortie.add(3); "abc".forEach { sortie.add(it.code.toByte()) }
        sortie.add(0xC0.toByte()); sortie.add(0x0C)
        assertEquals("abc", Dns.nomDemande(sortie.toByteArray()))
    }
}
