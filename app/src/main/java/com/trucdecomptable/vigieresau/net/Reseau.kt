package com.trucdecomptable.vigieresau.net

import com.trucdecomptable.vigieresau.data.EtatPasserelle

/**
 * Analyse des sorties système du téléphone. Toutes les fonctions d'ici sont
 * pures (elles prennent le texte brut en entrée) pour être testables sans
 * appareil : c'est le cœur du diagnostic quand la passerelle ne se comporte
 * pas comme prévu.
 */
object Reseau {

    data class InterfaceIp(val nom: String, val ip: String, val prefixe: Int)

    /** `ip -o -4 addr show` → 1: lo inet 127.0.0.1/8 … */
    fun interfaces(sortie: String): List<InterfaceIp> = sortie.lineSequence()
        .mapNotNull { ligne ->
            val morceaux = ligne.trim().split(Regex("\\s+"))
            val rangInet = morceaux.indexOf("inet")
            if (rangInet < 0 || rangInet + 1 >= morceaux.size) return@mapNotNull null
            val nom = morceaux.getOrNull(1)?.trimEnd(':')?.substringBefore('@')?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val (ip, prefixe) = decouperCidr(morceaux[rangInet + 1]) ?: return@mapNotNull null
            InterfaceIp(nom, ip, prefixe)
        }
        .toList()

    fun decouperCidr(cidr: String): Pair<String, Int>? {
        val parties = cidr.split('/')
        if (parties.size != 2) return null
        val prefixe = parties[1].toIntOrNull() ?: return null
        return parties[0] to prefixe
    }

    // ------------------------------------------------------------ détection

    /** Origine de la décision : sert à l'écran de diagnostic et au journal. */
    enum class Source(val libelle: String) {
        MANUELLE("manuelle"),
        DUMPSYS("dumpsys"),
        AUTOMATIQUE("automatique"),
        AUCUNE("aucune"),
    }

    /** Résultat d'un choix d'interface : laquelle, et pourquoi. */
    data class Choix(val interfaceRetenue: InterfaceIp?, val source: Source)

    /** Noms que les ROM donnent le plus souvent à l'interface du partage. */
    private val nomsConnus = setOf("ap0", "wlan1", "wlan2", "softap0", "swlan0", "ap_br_wlan1", "ap_br_wlan2")

    /** Un nom qui commence ainsi peut être un point d'accès Wi-Fi. */
    private val prefixesPartage = listOf("ap", "wlan", "softap", "swlan")

    /** Jamais un partage : VPN, tunnels, données mobiles, boucle locale. */
    private val prefixesExclus = listOf("tun", "tap", "wg", "ppp", "rmnet", "dummy", "lo")

    /** Sous-réseaux que le partage Android utilise par défaut. */
    private val prefixesIpPartage = listOf("192.168.43.", "192.168.42.")

    fun nomExclu(nom: String): Boolean = prefixesExclus.any { nom.lowercase().startsWith(it) }

    fun nomPartage(nom: String): Boolean = prefixesPartage.any { nom.lowercase().startsWith(it) }

    /** Adresse privée au sens RFC 1918 : 10/8, 172.16/12, 192.168/16. */
    fun estPrive(ip: String): Boolean {
        val octets = ip.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    /**
     * Interfaces qui peuvent porter le partage : ni la boucle locale, ni un
     * tunnel ou une interface mobile, ni l'uplink.
     */
    private fun candidates(interfaces: List<InterfaceIp>, interfaceUplink: String?): List<InterfaceIp> =
        interfaces.filter { it.nom != interfaceUplink && !nomExclu(it.nom) }

    /**
     * Détection automatique, par ordre de priorité :
     *  1. une interface désignée par dumpsys ([designees]) et retrouvée dans la liste ;
     *  2. un nom connu (`ap0`, `wlan1`, `softap0`, `swlan0`…) ;
     *  3. un sous-réseau de partage (192.168.43.x, 192.168.42.x) ;
     *  4. toute interface privée dont le nom commence par `ap`, `wlan`, `softap` ou `swlan`.
     */
    fun interfaceHotspot(
        interfaces: List<InterfaceIp>,
        interfaceUplink: String?,
        designees: List<String> = emptyList(),
    ): InterfaceIp? = choisirParDumpsys(interfaces, interfaceUplink, designees)
        ?: choisirParDetection(interfaces, interfaceUplink)

    private fun choisirParDumpsys(
        interfaces: List<InterfaceIp>,
        interfaceUplink: String?,
        designees: List<String>,
    ): InterfaceIp? {
        val candidates = candidates(interfaces, interfaceUplink)
        // Les noms de type Wi-Fi passent avant un éventuel partage USB ou Bluetooth.
        return designees.sortedBy { if (nomPartage(it)) 0 else 1 }
            .firstNotNullOfOrNull { nom -> candidates.firstOrNull { it.nom == nom } }
    }

    private fun choisirParDetection(interfaces: List<InterfaceIp>, interfaceUplink: String?): InterfaceIp? {
        val candidates = candidates(interfaces, interfaceUplink)
        return candidates.firstOrNull { it.nom in nomsConnus }
            ?: candidates.firstOrNull { c -> prefixesIpPartage.any { c.ip.startsWith(it) } }
            ?: candidates.firstOrNull { nomPartage(it.nom) && estPrive(it.ip) }
    }

    /**
     * Décision complète : le choix manuel est prioritaire et sans repli. Si
     * l'interface choisie est absente, rien n'est retenu (le partage est
     * probablement coupé) plutôt que de viser une autre interface à l'insu de
     * l'utilisateur. Sans choix manuel : dumpsys, puis détection automatique.
     */
    fun choisirInterface(
        interfaces: List<InterfaceIp>,
        interfaceUplink: String?,
        manuelle: String?,
        designees: List<String> = emptyList(),
    ): Choix {
        if (!manuelle.isNullOrBlank()) {
            val trouvee = interfaces.firstOrNull { it.nom == manuelle }
            return if (trouvee != null) Choix(trouvee, Source.MANUELLE) else Choix(null, Source.AUCUNE)
        }
        choisirParDumpsys(interfaces, interfaceUplink, designees)?.let { return Choix(it, Source.DUMPSYS) }
        choisirParDetection(interfaces, interfaceUplink)?.let { return Choix(it, Source.AUTOMATIQUE) }
        return Choix(null, Source.AUCUNE)
    }

    /**
     * Interfaces que `dumpsys connectivity` (section tethering) ou `dumpsys wifi`
     * désignent comme portant le partage, dans l'ordre d'apparition. Formats lus :
     *  - `wlan1 - TetheredState` ou `ap0 - LocalHotspotState` ;
     *  - `Tethered interfaces: [wlan1]` ;
     *  - `mApInterfaceName: wlan1` ou `mSoftApInterfaceName=ap0` (dumpsys wifi).
     * Les états « Available » et « Unavailable » ne désignent pas un partage actif.
     */
    fun interfacesDesigneesDumpsys(sortie: String): List<String> {
        val nom = "[A-Za-z][A-Za-z0-9_.\\-]*"
        val etats = Regex("^\\s*($nom)\\s+-\\s+(?:Tethered|LocalHotspot)State\\b", RegexOption.MULTILINE)
        val liste = Regex("tethered[^\\[\\n]*\\[([^\\]\\n]*)\\]", RegexOption.IGNORE_CASE)
        val wifi = Regex("\\bm?(?:Soft)?ApInterfaceName\\s*[:=]\\s*($nom)", RegexOption.IGNORE_CASE)

        val trouvees = mutableListOf<Pair<Int, String>>()
        etats.findAll(sortie).forEach { trouvees += it.range.first to it.groupValues[1] }
        liste.findAll(sortie).forEach { m ->
            m.groupValues[1].split(',', ' ').map { it.trim() }.filter { it.matches(Regex(nom)) }
                .forEach { trouvees += m.range.first to it }
        }
        wifi.findAll(sortie).forEach { trouvees += it.range.first to it.groupValues[1] }

        return trouvees.sortedBy { it.first }.map { it.second }
            .filter { !it.equals("null", ignoreCase = true) && !nomExclu(it) }
            .distinct()
    }

    /**
     * État de la passerelle à partir des sorties brutes. Entièrement pur : le
     * ViewModel ne fait que lire le téléphone puis appeler cette fonction.
     */
    fun construireEtat(
        racine: Boolean,
        interfaces: List<InterfaceIp>,
        interfaceUplink: String?,
        sortieDumpsys: String,
        manuelle: String?,
    ): EtatPasserelle {
        val designees = interfacesDesigneesDumpsys(sortieDumpsys)
        val choix = choisirInterface(interfaces, interfaceUplink, manuelle, designees)
        val hotspot = choix.interfaceRetenue
        return EtatPasserelle(
            racine = racine,
            hotspotActif = hotspot != null,
            interfaceHotspot = hotspot?.nom,
            sousReseau = hotspot?.let { sousReseau(it.ip, it.prefixe) },
            uplink = interfaceUplink,
            interfacesVues = interfaces,
            designeesDumpsys = designees,
            source = choix.source,
            choixManuel = manuelle?.takeIf { it.isNotBlank() },
        )
    }

    // ------------------------------------------------------- décision de démarrage

    /** Ce que l'app peut faire quand l'utilisateur appuie sur Démarrer. */
    sealed interface Verdict {
        data class Autorise(val interfaceHotspot: String, val source: Source) : Verdict
        object RootAbsent : Verdict
        object AucuneInterface : Verdict
        data class PartageAbsent(val vues: List<InterfaceIp>) : Verdict
        data class ManuelleAbsente(val nom: String, val vues: List<InterfaceIp>) : Verdict
    }

    /** Décision pure : à appeler avec un état qui vient d'être recalculé. */
    fun verdictDemarrage(etat: EtatPasserelle): Verdict = when {
        !etat.racine -> Verdict.RootAbsent
        etat.interfaceHotspot != null -> Verdict.Autorise(etat.interfaceHotspot, etat.source)
        etat.choixManuel != null -> Verdict.ManuelleAbsente(etat.choixManuel, etat.interfacesVues)
        etat.interfacesVues.isEmpty() -> Verdict.AucuneInterface
        else -> Verdict.PartageAbsent(etat.interfacesVues)
    }

    /**
     * Recalcule l'état PUIS décide : la décision ne repose jamais sur une
     * valeur mise en cache. [detecter] lit le téléphone à chaque appel.
     */
    fun evaluerDemarrage(detecter: () -> EtatPasserelle): Pair<EtatPasserelle, Verdict> {
        val frais = detecter()
        return frais to verdictDemarrage(frais)
    }

    /** « wlan0 192.168.1.24/24, ap0 192.168.43.1/24 » */
    fun libelleInterfaces(interfaces: List<InterfaceIp>): String =
        if (interfaces.isEmpty()) "aucune" else interfaces.joinToString(", ") { "${it.nom} ${it.ip}/${it.prefixe}" }

    /** Message affiché à l'utilisateur quand le démarrage est refusé ; null si autorisé. */
    fun messageRefus(verdict: Verdict): String? = when (verdict) {
        is Verdict.Autorise -> null
        Verdict.RootAbsent ->
            "Root indisponible : su n'a pas répondu. Accorde l'accès à l'app dans Magisk."
        Verdict.AucuneInterface ->
            "Aucune interface réseau lue malgré le root (la commande ip n'a rien rendu). " +
                "Ouvre le diagnostic ou relance l'app."
        is Verdict.PartageAbsent ->
            "Partage de connexion introuvable. Interfaces vues : ${libelleInterfaces(verdict.vues)}. " +
                "S'il est bien actif, choisis son interface dans le diagnostic."
        is Verdict.ManuelleAbsente ->
            "L'interface choisie (${verdict.nom}) est absente. Interfaces vues : " +
                "${libelleInterfaces(verdict.vues)}. Active le partage ou reviens à la détection automatique."
    }

    fun messagePortOccupe(port: Int, detail: String?): String =
        "Port DNS $port occupé ou inaccessible" + (detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            ". Un autre programme l'utilise peut-être : arrête-le puis réessaie."

    /** Ligne du journal au démarrage : interface retenue, interfaces vues, source de la décision. */
    fun ligneDecision(etat: EtatPasserelle): String =
        "Interface retenue : ${etat.interfaceHotspot ?: "aucune"} · source : ${etat.source.libelle} · " +
            "interfaces vues : ${libelleInterfaces(etat.interfacesVues)} · uplink : ${etat.uplink ?: "inconnu"} · " +
            "dumpsys : ${etat.designeesDumpsys.ifEmpty { listOf("rien") }.joinToString(", ")}"

    /** `ip route get 1.1.1.1` → « 1.1.1.1 via 192.168.1.1 dev wlan0 … » */
    fun uplink(sortie: String): String? {
        val morceaux = sortie.trim().split(Regex("\\s+"))
        val rang = morceaux.indexOf("dev")
        if (rang < 0) return null
        val nom = morceaux.getOrNull(rang + 1) ?: return null
        return nom.takeIf { it.isNotBlank() }
    }

    fun sousReseau(ip: String, prefixe: Int): String {
        val octets = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return "$ip/$prefixe"
        var valeur = octets.fold(0L) { acc, o -> (acc shl 8) or o.toLong() }
        val masque = if (prefixe == 0) 0L else (0xFFFFFFFFL shl (32 - prefixe)) and 0xFFFFFFFFL
        valeur = valeur and masque
        val base = (3 downTo 0).joinToString(".") { rang -> ((valeur shr (rang * 8)) and 0xFF).toString() }
        return "$base/$prefixe"
    }

    /** `/data/misc/dhcp/dnsmasq.leases` → expiration mac ip nom client-id */
    fun baux(contenu: String): List<Triple<String, String, String>> = contenu.lineSequence()
        .mapNotNull { ligne ->
            val morceaux = ligne.trim().split(Regex("\\s+"))
            if (morceaux.size < 4) return@mapNotNull null
            val mac = morceaux[1].lowercase()
            val ip = morceaux[2]
            val nom = morceaux[3].takeIf { it != "*" }.orEmpty()
            Triple(mac, ip, nom)
        }
        .toList()

    /** `ip neigh show dev ap0` → « 192.168.43.5 lladdr aa:bb:… REACHABLE » */
    fun voisins(sortie: String): List<Pair<String, String>> = sortie.lineSequence()
        .mapNotNull { ligne ->
            val morceaux = ligne.trim().split(Regex("\\s+"))
            val ip = morceaux.firstOrNull()?.takeIf { it.count { c -> c == '.' } == 3 } ?: return@mapNotNull null
            val rang = morceaux.indexOf("lladdr")
            val mac = if (rang >= 0) morceaux.getOrNull(rang + 1)?.lowercase().orEmpty() else ""
            ip to mac
        }
        .toList()

    /** Sous-réseau d'une IP en /24, pratique pour filtrer les voisins du hotspot. */
    fun prefixe24(ip: String): String = ip.split('.').take(3).joinToString(".")
}
