package com.trucdecomptable.vigieresau

import com.trucdecomptable.vigieresau.net.Compteurs
import com.trucdecomptable.vigieresau.net.Reseau
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReseauTest {

    @Test
    fun `lit les adresses des interfaces`() {
        val sortie = """
            1: lo    inet 127.0.0.1/8 scope host lo
            25: wlan0    inet 192.168.1.24/24 brd 192.168.1.255 scope global wlan0
            27: ap0    inet 192.168.43.1/24 brd 192.168.43.255 scope global ap0
        """.trimIndent()
        val interfaces = Reseau.interfaces(sortie)
        assertEquals(3, interfaces.size)
        assertEquals("ap0", interfaces[2].nom)
        assertEquals("192.168.43.1", interfaces[2].ip)
        assertEquals(24, interfaces[2].prefixe)
    }

    @Test
    fun `trouve l interface du partage et l uplink`() {
        val interfaces = listOf(
            Reseau.InterfaceIp("wlan0", "192.168.1.24", 24),
            Reseau.InterfaceIp("ap0", "192.168.43.1", 24),
        )
        assertEquals("ap0", Reseau.interfaceHotspot(interfaces, "wlan0")?.nom)
        assertEquals("wlan0", Reseau.uplink("1.1.1.1 via 192.168.1.1 dev wlan0 src 192.168.1.24"))
        assertNull(Reseau.uplink(""))
    }

    @Test
    fun `le sous-reseau se calcule depuis l ip et le prefixe`() {
        assertEquals("192.168.43.0/24", Reseau.sousReseau("192.168.43.1", 24))
        assertEquals("10.0.0.0/8", Reseau.sousReseau("10.4.9.12", 8))
        assertEquals("192.168.43", Reseau.prefixe24("192.168.43.77"))
    }

    @Test
    fun `lit les baux dhcp et les voisins`() {
        val baux = """
            1793114113 aa:bb:cc:dd:ee:01 192.168.43.7 android-phone *
            1793114999 aa:bb:cc:dd:ee:02 192.168.43.8 * *
        """.trimIndent()
        val lus = Reseau.baux(baux)
        assertEquals(2, lus.size)
        assertEquals("aa:bb:cc:dd:ee:01", lus[0].first)
        assertEquals("192.168.43.7", lus[0].second)
        assertEquals("android-phone", lus[0].third)
        assertEquals("", lus[1].third)

        val voisins = """
            192.168.43.7 dev ap0 lladdr AA:BB:CC:DD:EE:01 REACHABLE
            192.168.43.9 dev ap0 FAILED
        """.trimIndent()
        val vus = Reseau.voisins(voisins)
        assertEquals(2, vus.size)
        assertEquals("192.168.43.7" to "aa:bb:cc:dd:ee:01", vus[0])
        assertEquals("192.168.43.9" to "", vus[1])
    }

    @Test
    fun `compteurs iptables par appareil`() {
        val emis = """
            Chain VIGIE_EMIS (1 references)
                pkts      bytes target     prot opt in     out     source               destination
                  12      1440 RETURN     all  --  ap0    *       192.168.43.7         0.0.0.0/0
                   3       402 RETURN     all  --  ap0    *       192.168.43.8         0.0.0.0/0
        """.trimIndent()
        val recu = """
            Chain VIGIE_RECU (1 references)
                pkts      bytes target     prot opt in     out     source               destination
                  40     58210 RETURN     all  --  *      ap0     0.0.0.0/0            192.168.43.7
        """.trimIndent()
        val fusion = Compteurs.fusionner(emis, recu)
        assertEquals(2, fusion.size)
        assertEquals(1440L to 58210L, fusion["192.168.43.7"])
        assertEquals(402L to 0L, fusion["192.168.43.8"])
    }

    @Test
    fun `les volumes restent lisibles`() {
        assertEquals("512 o", com.trucdecomptable.vigieresau.data.octetsLisibles(512))
        assertTrue(com.trucdecomptable.vigieresau.data.octetsLisibles(58210).startsWith("56"))
        assertTrue(com.trucdecomptable.vigieresau.data.octetsLisibles(5_000_000).contains("Mo"))
    }
}
