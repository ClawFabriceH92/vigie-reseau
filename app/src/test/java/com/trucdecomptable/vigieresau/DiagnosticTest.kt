package com.trucdecomptable.vigieresau

import com.trucdecomptable.vigieresau.net.RelaisDns
import com.trucdecomptable.vigieresau.net.Reseau
import com.trucdecomptable.vigieresau.net.Reseau.InterfaceIp
import com.trucdecomptable.vigieresau.net.Reseau.Source
import com.trucdecomptable.vigieresau.net.Reseau.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Règles de détection élargie, source dumpsys, choix manuel et décision de démarrage. */
class DiagnosticTest {

    private fun i(nom: String, ip: String, prefixe: Int = 24) = InterfaceIp(nom, ip, prefixe)

    private val maison = i("wlan0", "192.168.1.24")

    // ------------------------------------------------------- noms inhabituels

    @Test
    fun `reconnait les noms inhabituels d interface de partage`() {
        listOf("wlan3", "ap_br_wlan0", "softap1", "swlan1", "ap1", "wlan9").forEach { nom ->
            val trouve = Reseau.interfaceHotspot(listOf(maison, i(nom, "192.168.77.1")), "wlan0")
            assertEquals("nom $nom", nom, trouve?.nom)
        }
    }

    @Test
    fun `reconnait les autres sous-reseaux prives`() {
        assertEquals("wlan3", Reseau.interfaceHotspot(listOf(maison, i("wlan3", "10.20.30.1", 8)), "wlan0")?.nom)
        assertEquals("ap_br_wlan0", Reseau.interfaceHotspot(listOf(maison, i("ap_br_wlan0", "172.20.10.1")), "wlan0")?.nom)
        assertEquals("softap1", Reseau.interfaceHotspot(listOf(maison, i("softap1", "192.168.200.1")), "wlan0")?.nom)
    }

    @Test
    fun `une adresse publique ou hors plage privee n est pas un partage`() {
        assertNull(Reseau.interfaceHotspot(listOf(maison, i("wlan3", "8.8.8.8")), "wlan0"))
        assertNull(Reseau.interfaceHotspot(listOf(maison, i("wlan3", "172.32.0.1")), "wlan0"))
        assertNull(Reseau.interfaceHotspot(listOf(maison, i("wlan3", "172.15.0.1")), "wlan0"))
        assertNull(Reseau.interfaceHotspot(listOf(maison, i("wlan3", "100.64.0.1")), "wlan0"))
        assertNull(Reseau.interfaceHotspot(listOf(maison, i("wlan3", "169.254.1.1")), "wlan0"))
    }

    @Test
    fun `estPrive couvre exactement les trois plages`() {
        assertTrue(Reseau.estPrive("10.0.0.1"))
        assertTrue(Reseau.estPrive("172.16.0.1"))
        assertTrue(Reseau.estPrive("172.31.255.254"))
        assertTrue(Reseau.estPrive("192.168.0.1"))
        assertFalse(Reseau.estPrive("172.32.0.1"))
        assertFalse(Reseau.estPrive("192.169.0.1"))
        assertFalse(Reseau.estPrive("11.0.0.1"))
        assertFalse(Reseau.estPrive("abc"))
        assertFalse(Reseau.estPrive("10.0.0"))
        assertFalse(Reseau.estPrive("10.0.0.300"))
    }

    @Test
    fun `un nom sans rapport avec le wifi n est pas retenu meme en prive`() {
        assertNull(Reseau.interfaceHotspot(listOf(maison, i("eth0", "192.168.5.1"), i("bt-pan", "192.168.44.1")), "wlan0"))
    }

    // ------------------------------------------------------------- exclusions

    @Test
    fun `les interfaces tun tap wg ppp rmnet dummy lo ne sont jamais un partage`() {
        listOf("tun0", "tap0", "wg0", "ppp0", "rmnet_data0", "rmnet_ipa0", "dummy0", "lo").forEach { nom ->
            // Même avec le sous-réseau classique du partage : l'exclusion l'emporte.
            assertNull("nom $nom", Reseau.interfaceHotspot(listOf(maison, i(nom, "192.168.43.1")), "wlan0"))
            assertNull("nom $nom", Reseau.interfaceHotspot(listOf(i(nom, "10.1.2.3")), null))
        }
    }

    @Test
    fun `l uplink est exclu meme s il porte un nom de partage`() {
        assertNull(Reseau.interfaceHotspot(listOf(i("wlan0", "192.168.43.77")), "wlan0"))
        assertNull(Reseau.interfaceHotspot(listOf(i("wlan1", "192.168.1.5")), "wlan1"))
    }

    @Test
    fun `un VPN actif ne masque pas le vrai partage`() {
        val vues = listOf(i("tun0", "10.8.0.2", 24), i("rmnet_data0", "10.99.1.2", 30), maison, i("wlan1", "192.168.43.1"))
        assertEquals("wlan1", Reseau.interfaceHotspot(vues, "rmnet_data0")?.nom)
    }

    // ---------------------------------------------------------------- priorité

    @Test
    fun `nom connu avant sous-reseau avant regle generale`() {
        val generale = i("wlan7", "10.0.0.1")
        val sousReseau = i("bridge0", "192.168.43.1")
        val connu = i("ap0", "192.168.50.1")
        assertEquals("ap0", Reseau.interfaceHotspot(listOf(generale, sousReseau, connu), "wlan0")?.nom)
        assertEquals("bridge0", Reseau.interfaceHotspot(listOf(generale, sousReseau), "wlan0")?.nom)
        assertEquals("wlan7", Reseau.interfaceHotspot(listOf(generale), "wlan0")?.nom)
    }

    @Test
    fun `le comportement historique est conserve`() {
        assertEquals("wlan2", Reseau.interfaceHotspot(listOf(maison, i("wlan2", "192.168.43.1")), "wlan0")?.nom)
        assertEquals("swlan0", Reseau.interfaceHotspot(listOf(maison, i("swlan0", "192.168.42.129")), "wlan0")?.nom)
        assertNull(Reseau.interfaceHotspot(listOf(maison), "wlan0"))
        assertNull(Reseau.interfaceHotspot(emptyList(), null))
    }

    // ----------------------------------------------------------------- dumpsys

    @Test
    fun `lit l interface du partage dans dumpsys connectivity`() {
        val sortie = """
            Tethering:
              Configuration: TetheringConfiguration{...}
              Tether state:
                rndis0 - AvailableState - lastError = 0
                wlan3 - TetheredState - lastError = 0
                p2p0 - UnavailableState - lastError = 0
        """.trimIndent()
        assertEquals(listOf("wlan3"), Reseau.interfacesDesigneesDumpsys(sortie))
    }

    @Test
    fun `lit les listes d interfaces partagees`() {
        val sortie = "  Tethered interfaces: [ap_br_wlan0, rndis0]\n  Local only: []"
        assertEquals(listOf("ap_br_wlan0", "rndis0"), Reseau.interfacesDesigneesDumpsys(sortie))
        assertEquals(emptyList<String>(), Reseau.interfacesDesigneesDumpsys("Tethered interfaces: []"))
    }

    @Test
    fun `lit l interface du point d acces dans dumpsys wifi`() {
        val sortie = """
            SoftApManager{...}
              mApInterfaceName: wlan2
              mSoftApInterfaceName=null
        """.trimIndent()
        assertEquals(listOf("wlan2"), Reseau.interfacesDesigneesDumpsys(sortie))
        assertEquals(listOf("ap0"), Reseau.interfacesDesigneesDumpsys("mApInterfaceName=ap0"))
    }

    @Test
    fun `dumpsys ignore le bruit les doublons et les interfaces exclues`() {
        val sortie = """
            tun0 - TetheredState
            wlan1 - TetheredState
            Tethered interfaces: [wlan1]
            mApInterfaceName: wlan1
            rien d'utile ici
        """.trimIndent()
        assertEquals(listOf("wlan1"), Reseau.interfacesDesigneesDumpsys(sortie))
        assertEquals(emptyList<String>(), Reseau.interfacesDesigneesDumpsys(""))
        assertEquals(emptyList<String>(), Reseau.interfacesDesigneesDumpsys("n'importe quoi\nautre ligne"))
    }

    @Test
    fun `une interface designee par dumpsys passe en premier si elle est dans la liste`() {
        val vues = listOf(maison, i("ap0", "192.168.43.1"), i("bridge7", "10.5.5.1", 8))
        assertEquals("bridge7", Reseau.interfaceHotspot(vues, "wlan0", listOf("bridge7"))?.nom)
        // Absente de la liste : on retombe sur la détection.
        assertEquals("ap0", Reseau.interfaceHotspot(vues, "wlan0", listOf("fantome0"))?.nom)
        // Exclue ou uplink : jamais retenue, même désignée.
        assertEquals("ap0", Reseau.interfaceHotspot(vues + i("tun0", "10.8.0.2"), "wlan0", listOf("tun0"))?.nom)
        assertEquals("ap0", Reseau.interfaceHotspot(vues, "wlan0", listOf("wlan0"))?.nom)
    }

    @Test
    fun `dumpsys prefere une interface wifi a un partage usb`() {
        val vues = listOf(maison, i("rndis0", "192.168.42.129"), i("wlan1", "192.168.43.1"))
        assertEquals("wlan1", Reseau.interfaceHotspot(vues, "wlan0", listOf("rndis0", "wlan1"))?.nom)
    }

    // ------------------------------------------------------------ choix manuel

    @Test
    fun `le choix manuel est prioritaire sur dumpsys et la detection`() {
        val vues = listOf(maison, i("ap0", "192.168.43.1"), i("wlan3", "10.0.0.1", 8), i("eth9", "192.168.9.1"))
        val choix = Reseau.choisirInterface(vues, "wlan0", manuelle = "eth9", designees = listOf("wlan3"))
        assertEquals("eth9", choix.interfaceRetenue?.nom)
        assertEquals(Source.MANUELLE, choix.source)
    }

    @Test
    fun `le choix manuel peut viser une interface que la detection exclurait`() {
        val vues = listOf(maison, i("tun0", "10.8.0.2"))
        assertEquals("tun0", Reseau.choisirInterface(vues, "wlan0", "tun0").interfaceRetenue?.nom)
    }

    @Test
    fun `un choix manuel absent ne retombe pas sur une autre interface`() {
        val vues = listOf(maison, i("ap0", "192.168.43.1"))
        val choix = Reseau.choisirInterface(vues, "wlan0", manuelle = "wlan9")
        assertNull(choix.interfaceRetenue)
        assertEquals(Source.AUCUNE, choix.source)
    }

    @Test
    fun `sans choix manuel la source est dumpsys puis automatique`() {
        val vues = listOf(maison, i("ap0", "192.168.43.1"), i("wlan3", "10.0.0.1", 8))
        assertEquals(Source.DUMPSYS, Reseau.choisirInterface(vues, "wlan0", null, listOf("wlan3")).source)
        assertEquals(Source.AUTOMATIQUE, Reseau.choisirInterface(vues, "wlan0", null).source)
        assertEquals(Source.AUTOMATIQUE, Reseau.choisirInterface(vues, "wlan0", "  ").source)
        assertEquals(Source.AUCUNE, Reseau.choisirInterface(listOf(maison), "wlan0", null).source)
    }

    // ------------------------------------------------- de la sortie brute à l'état

    @Test
    fun `construit l etat depuis les sorties brutes`() {
        val vues = Reseau.interfaces(
            """
            1: lo    inet 127.0.0.1/8 scope host lo
            25: wlan0    inet 192.168.1.24/24 brd 192.168.1.255 scope global wlan0
            27: ap_br_wlan0    inet 10.42.0.1/24 brd 10.42.0.255 scope global ap_br_wlan0
            """.trimIndent(),
        )
        val etat = Reseau.construireEtat(true, vues, "wlan0", "", null)
        assertTrue(etat.hotspotActif)
        assertEquals("ap_br_wlan0", etat.interfaceHotspot)
        assertEquals("10.42.0.0/24", etat.sousReseau)
        assertEquals(Source.AUTOMATIQUE, etat.source)
        assertEquals(3, etat.interfacesVues.size)
    }

    @Test
    fun `l etat garde la trace de dumpsys et du choix manuel`() {
        val vues = listOf(maison, i("wlan3", "10.0.0.1", 8))
        val etat = Reseau.construireEtat(true, vues, "wlan0", "wlan3 - TetheredState", null)
        assertEquals(Source.DUMPSYS, etat.source)
        assertEquals(listOf("wlan3"), etat.designeesDumpsys)
        val manuel = Reseau.construireEtat(true, vues, "wlan0", "", "wlan3")
        assertEquals(Source.MANUELLE, manuel.source)
        assertEquals("wlan3", manuel.choixManuel)
    }

    @Test
    fun `les noms avec arobase sont nettoyes et un uplink sans dev est ignore`() {
        val vues = Reseau.interfaces("5: wlan3@if4    inet 10.0.0.1/24 scope global wlan3")
        assertEquals("wlan3", vues.single().nom)
        assertNull(Reseau.uplink("RTNETLINK answers: Network is unreachable"))
    }

    // ------------------------------------------------------ décision de démarrage

    private fun etat(racine: Boolean, vues: List<InterfaceIp>, manuelle: String? = null, dumpsys: String = "") =
        Reseau.construireEtat(racine, vues, "wlan0", dumpsys, manuelle)

    @Test
    fun `root absent est distingue du partage absent`() {
        val verdict = Reseau.verdictDemarrage(etat(false, listOf(maison)))
        assertEquals(Verdict.RootAbsent, verdict)
        assertTrue(Reseau.messageRefus(verdict)!!.contains("Root"))
        // Root absent l'emporte même si une interface de partage est visible.
        assertEquals(Verdict.RootAbsent, Reseau.verdictDemarrage(etat(false, listOf(maison, i("ap0", "192.168.43.1")))))
    }

    @Test
    fun `partage absent cite les interfaces vues`() {
        val verdict = Reseau.verdictDemarrage(etat(true, listOf(maison, i("rmnet_data0", "10.1.2.3", 30))))
        assertTrue(verdict is Verdict.PartageAbsent)
        val message = Reseau.messageRefus(verdict)!!
        assertTrue(message, message.contains("introuvable"))
        assertTrue(message, message.contains("wlan0 192.168.1.24/24"))
        assertTrue(message, message.contains("rmnet_data0 10.1.2.3/30"))
        assertFalse(message.contains("Active d'abord"))
    }

    @Test
    fun `liste vide et interface manuelle absente ont leur message`() {
        val vide = Reseau.verdictDemarrage(etat(true, emptyList()))
        assertEquals(Verdict.AucuneInterface, vide)
        assertTrue(Reseau.messageRefus(vide)!!.contains("Aucune interface"))

        val absente = Reseau.verdictDemarrage(etat(true, listOf(maison), manuelle = "wlan9"))
        assertTrue(absente is Verdict.ManuelleAbsente)
        val message = Reseau.messageRefus(absente)!!
        assertTrue(message, message.contains("wlan9"))
        assertTrue(message, message.contains("wlan0 192.168.1.24/24"))
        assertTrue(message, message.contains("automatique"))
    }

    @Test
    fun `le demarrage est autorise avec l interface et la source`() {
        val verdict = Reseau.verdictDemarrage(etat(true, listOf(maison, i("wlan3", "10.0.0.1", 8))))
        assertEquals(Verdict.Autorise("wlan3", Source.AUTOMATIQUE), verdict)
        assertNull(Reseau.messageRefus(verdict))
    }

    @Test
    fun `la decision repose sur l etat recalcule et jamais sur un etat perime`() {
        // Premier affichage : le partage n'est pas encore actif.
        val perime = etat(true, listOf(maison))
        assertTrue(Reseau.verdictDemarrage(perime) is Verdict.PartageAbsent)

        // L'utilisateur active le partage, puis appuie sur Démarrer : la lecture suivante le voit.
        var lectures = 0
        val detecter = {
            lectures++
            etat(true, listOf(maison, i("ap0", "192.168.43.1")))
        }
        val (frais, verdict) = Reseau.evaluerDemarrage(detecter)
        assertEquals(1, lectures)
        assertEquals(Verdict.Autorise("ap0", Source.AUTOMATIQUE), verdict)
        assertEquals("ap0", frais.interfaceHotspot)
    }

    @Test
    fun `chaque demarrage relit le telephone`() {
        var partage = false
        val detecter = { etat(true, if (partage) listOf(maison, i("wlan1", "192.168.43.1")) else listOf(maison)) }
        assertTrue(Reseau.evaluerDemarrage(detecter).second is Verdict.PartageAbsent)
        partage = true
        assertTrue(Reseau.evaluerDemarrage(detecter).second is Verdict.Autorise)
        partage = false
        assertTrue(Reseau.evaluerDemarrage(detecter).second is Verdict.PartageAbsent)
    }

    // ------------------------------------------------------ messages et journal

    @Test
    fun `le message de port occupe donne le port et la cause`() {
        val avec = Reseau.messagePortOccupe(5353, "Address already in use")
        assertTrue(avec, avec.contains("5353") && avec.contains("Address already in use"))
        val sans = Reseau.messagePortOccupe(5353, null)
        assertTrue(sans, sans.contains("5353") && !sans.contains("()"))
    }

    @Test
    fun `la ligne du journal cite interface retenue interfaces vues et source`() {
        val vues = listOf(maison, i("wlan3", "10.0.0.1", 8))
        val auto = Reseau.ligneDecision(etat(true, vues))
        assertTrue(auto, auto.contains("Interface retenue : wlan3"))
        assertTrue(auto, auto.contains("source : automatique"))
        assertTrue(auto, auto.contains("wlan0 192.168.1.24/24, wlan3 10.0.0.1/8"))

        val manuel = Reseau.ligneDecision(etat(true, vues, manuelle = "wlan0"))
        assertTrue(manuel, manuel.contains("Interface retenue : wlan0") && manuel.contains("source : manuelle"))

        val dumpsys = Reseau.ligneDecision(etat(true, vues, dumpsys = "wlan3 - TetheredState"))
        assertTrue(dumpsys, dumpsys.contains("source : dumpsys") && dumpsys.contains("dumpsys : wlan3"))

        val aucune = Reseau.ligneDecision(etat(true, listOf(maison)))
        assertTrue(aucune, aucune.contains("Interface retenue : aucune") && aucune.contains("source : aucune"))
    }

    @Test
    fun `les ports du relais evitent celui du mDNS d Android`() {
        // mdnsd occupe 5353 en permanence : il ne doit jamais être essayé en premier.
        assertTrue(RelaisDns.PORT_CANDIDATS.isNotEmpty())
        assertTrue(RelaisDns.PORT_CANDIDATS.first() != 5353)
        assertEquals(RelaisDns.PORT, RelaisDns.PORT_CANDIDATS.first())
        assertEquals(RelaisDns.PORT_CANDIDATS.size, RelaisDns.PORT_CANDIDATS.distinct().size)
        assertTrue(RelaisDns.PORT_CANDIDATS.all { it in 1024..65535 })
    }

    @Test
    fun `la redirection n est reconnue que si iptables la liste vraiment`() {
        val reelle = "-A PREROUTING -i ap0 -p udp -m udp --dport 53 -j REDIRECT --to-ports 35353"
        assertTrue(Reseau.regleRedirectionPresente(reelle, "ap0", 35353))
        // Mauvais port, mauvaise interface, chaîne vide : pas de redirection.
        assertFalse(Reseau.regleRedirectionPresente(reelle, "ap0", 5353))
        assertFalse(Reseau.regleRedirectionPresente(reelle, "wlan1", 35353))
        assertFalse(Reseau.regleRedirectionPresente("", "ap0", 35353))
    }

    @Test
    fun `une redirection laissee par une session tuee est retiree`() {
        val sortie = listOf(
            "-P PREROUTING ACCEPT",
            "-A PREROUTING -i ap0 -p udp -m udp --dport 53 -j REDIRECT --to-ports 35353",
            "-A PREROUTING -i ap0 -p udp -m udp --dport 8080 -j DNAT --to-destination 192.168.43.1:8080",
            "-A PREROUTING -i ap0 -p udp -m udp --dport 53 -j REDIRECT --to-ports 9999",
        ).joinToString("\n")
        val ports = listOf(35353, 45353)
        val lignes = Reseau.lignesRedirection(sortie, ports)
        assertEquals(1, lignes.size)
        // Une autre redirection (port inconnu, autre type) ne doit pas être touchée.
        assertEquals(
            listOf("iptables -t nat -D PREROUTING -i ap0 -p udp -m udp --dport 53 -j REDIRECT --to-ports 35353"),
            Reseau.commandesNettoyage(sortie, ports),
        )
        assertTrue(Reseau.commandesNettoyage("", ports).isEmpty())
    }

    @Test
    fun `le message d absence de DNS cite les ports essayes et rassure sur le reste`() {
        val texte = Reseau.messageDnsIndisponible(listOf(35353, 45353), "Address already in use")
        assertTrue(texte, texte.contains("35353") && texte.contains("45353"))
        assertTrue(texte, texte.contains("Address already in use"))
        assertTrue(texte, texte.contains("appareils", ignoreCase = true))
        assertTrue(Reseau.messageDnsIndisponible(emptyList(), null).isNotBlank())
    }

    @Test
    fun `le resume de l etat reste coherent`() {
        assertTrue(etat(false, emptyList()).resume.contains("root"))
        assertTrue(etat(true, listOf(maison, i("ap0", "192.168.43.1"))).resume.contains("ap0"))
        assertTrue(etat(true, listOf(maison), manuelle = "wlan9").resume.contains("wlan9"))
        assertEquals("Aucun partage de connexion détecté", etat(true, listOf(maison)).resume)
    }
}
