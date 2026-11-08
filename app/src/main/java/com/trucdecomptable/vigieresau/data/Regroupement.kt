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
