package com.trucdecomptable.vigieresau.net

/**
 * Lecture d'une question DNS dans une trame brute. Sert au relais : c'est le
 * nom demandé qui intéresse Fabrice (« quel appareil visite quel domaine »),
 * pas la réponse.
 */
object Dns {

    /**
     * Extrait le nom de domaine d'une requête DNS. Null si la trame n'est pas
     * une question lisible (réponse, compression, trame tronquée…).
     */
    fun nomDemande(paquet: ByteArray, longueur: Int = paquet.size): String? {
        if (longueur < 13) return null
        val fanions = ((paquet[2].toInt() and 0xFF) shl 8) or (paquet[3].toInt() and 0xFF)
        // QR = 1 : c'est une réponse, pas une question.
        if (fanions and 0x8000 != 0) return null
        val questions = ((paquet[4].toInt() and 0xFF) shl 8) or (paquet[5].toInt() and 0xFF)
        if (questions == 0) return null

        val morceaux = mutableListOf<String>()
        var position = 12
        while (position < longueur) {
            val taille = paquet[position].toInt() and 0xFF
            if (taille == 0) return morceaux.joinToString(".").ifBlank { null }
            // Un pointeur de compression sort de la question : on s'arrête là.
            if (taille and 0xC0 != 0) return morceaux.joinToString(".").ifBlank { null }
            val debut = position + 1
            val fin = debut + taille
            if (fin > longueur) return null
            morceaux.add(String(paquet, debut, taille, Charsets.US_ASCII))
            position = fin
        }
        return morceaux.joinToString(".").ifBlank { null }
    }
}
