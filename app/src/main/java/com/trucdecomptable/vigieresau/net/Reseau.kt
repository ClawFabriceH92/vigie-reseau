package com.trucdecomptable.vigieresau.net

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
            val nom = morceaux.getOrNull(1)?.trimEnd(':') ?: return@mapNotNull null
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

    /**
     * L'interface du partage de connexion : Android utilise `ap0`, `wlan1`,
     * `softap0` ou `swlan0` selon la ROM. On retient celle qui porte un
     * sous-réseau de partage (192.168.43.x, 192.168.42.x…) et qui n'est pas
     * l'interface de l'uplink.
     */
    fun interfaceHotspot(interfaces: List<InterfaceIp>, interfaceUplink: String?): InterfaceIp? {
        val nomsConnus = setOf("ap0", "wlan1", "wlan2", "softap0", "swlan0", "ap_br_wlan1", "ap_br_wlan2")
        val candidates = interfaces.filter { it.nom != interfaceUplink && it.nom != "lo" }
        return candidates.firstOrNull { it.nom in nomsConnus }
            ?: candidates.firstOrNull { it.ip.startsWith("192.168.43.") || it.ip.startsWith("192.168.42.") }
    }

    /** `ip route get 1.1.1.1` → « 1.1.1.1 via 192.168.1.1 dev wlan0 … » */
    fun uplink(sortie: String): String? {
        val morceaux = sortie.trim().split(Regex("\\s+"))
        val rang = morceaux.indexOf("dev")
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
