package com.trucdecomptable.vigieresau.data

/** Un appareil vu sur le partage de connexion. */
data class Appareil(
    val ip: String,
    val mac: String = "",
    val nom: String = "",
    val octetsRecus: Long = 0,
    val octetsEmis: Long = 0,
    val requetes: Int = 0,
) {
    /** Ce qu'on affiche : le nom DHCP s'il existe, sinon la MAC, sinon l'IP. */
    val libelle: String
        get() = nom.ifBlank { mac.ifBlank { ip } }

    val total: Long get() = octetsRecus + octetsEmis
}

/** Une question DNS observée : quel appareil a demandé quel domaine. */
data class RequeteDns(
    val horodatage: Long,
    val ipSource: String,
    val domaine: String,
)

/** Ligne du journal. */
data class Evenement(
    val horodatage: Long,
    val alerte: Boolean,
    val message: String,
)

/** État de la passerelle, recalculé à chaque rafraîchissement. */
data class EtatPasserelle(
    val racine: Boolean = false,
    val hotspotActif: Boolean = false,
    val interfaceHotspot: String? = null,
    val sousReseau: String? = null,
    val uplink: String? = null,
    val surveillance: Boolean = false,
    val domaineSurveille: String = "",
) {
    val resume: String
        get() = when {
            !racine -> "Accès root absent : les données des appareils sont invisibles"
            hotspotActif && uplink != null -> "Hotspot actif sur $interfaceHotspot · uplink $uplink"
            hotspotActif -> "Hotspot actif sur $interfaceHotspot · aucun uplink détecté"
            else -> "Aucun partage de connexion actif"
        }
}

/** Formatage lisible des volumes, sans dépendance. */
fun octetsLisibles(octets: Long): String {
    if (octets < 1024) return "$octets o"
    val unites = listOf("Ko", "Mo", "Go", "To")
    var valeur = octets.toDouble() / 1024
    var rang = 0
    while (valeur >= 1024 && rang < unites.lastIndex) {
        valeur /= 1024
        rang++
    }
    return String.format(java.util.Locale.FRANCE, "%.1f %s", valeur, unites[rang])
}
