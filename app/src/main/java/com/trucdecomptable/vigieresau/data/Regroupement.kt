package com.trucdecomptable.vigieresau.data

/** Un domaine demandé par un appareil, avec le nombre d'appels et la dernière date. */
data class DomaineVu(
    val domaine: String,
    val occurrences: Int,
    val dernier: Long,
)

/** Toutes les requêtes d'un appareil, prêtes à afficher. */
data class RequetesAppareil(
    val ip: String,
    val domaines: List<DomaineVu>,
    val total: Int,
    val dernier: Long,
)

/**
 * Titre et sous-titre d'un appareil : le nom quand on le connaît (nom DHCP, à
 * défaut la MAC), et l'adresse rappelée juste en dessous pour s'y retrouver.
 * Rend (titre, adresse) ; l'adresse est vide quand le titre est déjà l'IP.
 */
fun etiquette(libelle: String?, ip: String): Pair<String, String> {
    val nom = libelle?.trim().orEmpty()
    return if (nom.isBlank() || nom == ip) ip to "" else nom to ip
}

/**
 * Regroupe les requêtes DNS par appareil, pour l'onglet « Requêtes ».
 *
 * [filtre] ne garde que les domaines qui contiennent le texte (insensible à la
 * casse) : un appareil dont plus aucun domaine ne correspond disparaît. Les
 * appareils sont triés du plus récent au plus ancien, et leurs domaines du plus
 * demandé au moins demandé.
 */
fun regrouperParAppareil(requetes: List<RequeteDns>, filtre: String = ""): List<RequetesAppareil> {
    val texte = filtre.trim()
    val retenues = if (texte.isBlank()) requetes else requetes.filter { it.domaine.contains(texte, ignoreCase = true) }
    return retenues.groupBy { it.ipSource }
        .map { (ip, liste) ->
            val domaines = liste.groupBy { it.domaine }
                .map { (domaine, occurrences) ->
                    DomaineVu(domaine, occurrences.size, occurrences.maxOf { it.horodatage })
                }
                .sortedWith(compareByDescending<DomaineVu> { it.occurrences }.thenByDescending { it.dernier })
            RequetesAppareil(ip = ip, domaines = domaines, total = liste.size, dernier = liste.maxOf { it.horodatage })
        }
        .sortedByDescending { it.dernier }
}
