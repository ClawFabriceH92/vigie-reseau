package com.trucdecomptable.vigieresau.net

/**
 * Comptage du trafic par appareil. Android ne donne pas ces compteurs à une
 * app : on crée deux chaînes iptables dédiées, une règle par IP client, et on
 * relit les compteurs. C'est peu coûteux et ça survit aux redémarrages du
 * relais DNS.
 */
object Compteurs {

    /** Trafic émis par les clients (source = IP du client). */
    const val CHAINE_EMIS = "VIGIE_EMIS"

    /** Trafic reçu par les clients (destination = IP du client). */
    const val CHAINE_RECU = "VIGIE_RECU"

    /** Crée les chaînes et les branche sur FORWARD. Idempotent. */
    fun preparer(): Boolean {
        val commandes = listOf(
            "iptables -w -N $CHAINE_EMIS 2>/dev/null; iptables -w -N $CHAINE_RECU 2>/dev/null",
            "iptables -w -C FORWARD -j $CHAINE_EMIS 2>/dev/null || iptables -w -I FORWARD 1 -j $CHAINE_EMIS",
            "iptables -w -C FORWARD -j $CHAINE_RECU 2>/dev/null || iptables -w -I FORWARD 2 -j $CHAINE_RECU",
        )
        return commandes.all { Racine.exec(it) != null }
    }

    /** Ajoute les deux règles de comptage pour [ip], sans doublon. */
    fun suivre(ip: String): Boolean {
        val emis = "iptables -w -C $CHAINE_EMIS -s $ip -j RETURN 2>/dev/null || " +
            "iptables -w -A $CHAINE_EMIS -s $ip -j RETURN"
        val recu = "iptables -w -C $CHAINE_RECU -d $ip -j RETURN 2>/dev/null || " +
            "iptables -w -A $CHAINE_RECU -d $ip -j RETURN"
        return Racine.exec("$emis; $recu") != null
    }

    /**
     * Relit les compteurs. [emis]/[recu] sont les sorties de
     * `iptables -w -nvx -L <chaîne>` ; on rend IP → (octets émis, octets reçus).
     */
    fun fusionner(emis: String, recu: String): Map<String, Pair<Long, Long>> {
        val sortants = parser(emis, source = true)
        val entrants = parser(recu, source = false)
        val ips = sortants.keys + entrants.keys
        return ips.associateWith { ip -> (sortants[ip] ?: 0L) to (entrants[ip] ?: 0L) }
    }

    /**
     * Une ligne de `iptables -nvx -L` :
     * `   12  1440 RETURN  all  --  ap0  *  192.168.43.5  0.0.0.0/0`
     */
    fun parser(sortie: String, source: Boolean): Map<String, Long> {
        val resultat = mutableMapOf<String, Long>()
        sortie.lineSequence().forEach { ligne ->
            val morceaux = ligne.trim().split(Regex("\\s+"))
            if (morceaux.size < 9) return@forEach
            val octets = morceaux[1].toLongOrNull() ?: return@forEach
            if (morceaux[2] != "RETURN") return@forEach
            val adresse = morceaux[if (source) 7 else 8].substringBefore('/')
            if (adresse.count { it == '.' } != 3) return@forEach
            resultat[adresse] = octets
        }
        return resultat
    }

    /** Retire tout ce que l'app a installé (chaînes et règles). */
    fun arreter() {
        Racine.exec(
            "iptables -w -D FORWARD -j $CHAINE_EMIS 2>/dev/null; " +
                "iptables -w -D FORWARD -j $CHAINE_RECU 2>/dev/null; " +
                "iptables -w -F $CHAINE_EMIS 2>/dev/null; " +
                "iptables -w -F $CHAINE_RECU 2>/dev/null; " +
                "iptables -w -X $CHAINE_EMIS 2>/dev/null; " +
                "iptables -w -X $CHAINE_RECU 2>/dev/null",
        )
    }
}
