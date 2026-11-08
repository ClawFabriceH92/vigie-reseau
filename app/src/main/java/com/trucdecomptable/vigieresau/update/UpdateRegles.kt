package com.trucdecomptable.vigieresau.update

import java.io.File

/**
 * Règles de décision de la mise à jour, volontairement pures (aucun Android,
 * aucun réseau) pour être testées en JVM.
 */

/** États du flux de mise à jour, observés par le dialogue et par les réglages. */
sealed interface UpdateState {
    data object Idle : UpdateState

    /** Contrôle manuel en cours (le bouton des réglages affiche l'attente). */
    data object Checking : UpdateState

    data class Available(val version: String, val notes: String, val apkUrl: String) : UpdateState

    /** [silencieux] : pré-téléchargement non demandé, le dialogue reste fermé. */
    data class Downloading(val version: String, val progress: Float, val silencieux: Boolean = false) : UpdateState

    data class ReadyToInstall(val version: String, val file: File) : UpdateState

    /** Réponse réelle de GitHub : aucune version plus récente. */
    data class UpToDate(val version: String) : UpdateState

    /** [pendantTelechargement] distingue un échec de téléchargement d'un échec de contrôle. */
    data class Failed(val detail: String?, val pendantTelechargement: Boolean = false) : UpdateState
}

internal object UpdateRegles {

    /** Un APK est une archive ZIP : les deux premiers octets sont « PK ». */
    fun estUnApk(entete: ByteArray, lus: Int = entete.size): Boolean =
        lus >= 2 && entete[0] == 'P'.code.toByte() && entete[1] == 'K'.code.toByte()

    /** Un fichier tronqué est refusé quand la taille annoncée est connue ([attendu] <= 0 : inconnue). */
    fun telechargementComplet(ecrits: Long, attendu: Long): Boolean =
        ecrits > 0 && (attendu <= 0 || ecrits == attendu)

    /**
     * Le dialogue s'ouvre quelle que soit la page, sauf pour une version que
     * l'utilisateur a repoussée ([reportee]) et pour un pré-téléchargement silencieux.
     * Un échec de contrôle ne passe jamais par le dialogue : il va dans les réglages.
     */
    fun dialogueVisible(etat: UpdateState, reportee: String?): Boolean = when (etat) {
        is UpdateState.Available -> etat.version != reportee
        is UpdateState.Downloading -> !etat.silencieux && etat.version != reportee
        is UpdateState.ReadyToInstall -> etat.version != reportee
        is UpdateState.Failed -> etat.pendantTelechargement
        else -> false
    }

    /** Un contrôle automatique n'a rien à faire si une mise à jour est déjà en main. */
    fun miseAJourEnMain(etat: UpdateState): Boolean =
        etat is UpdateState.Available || etat is UpdateState.Downloading || etat is UpdateState.ReadyToInstall

    /** Statut lisible des réglages : « à jour » n'apparaît que pour une vraie réponse. */
    fun libelleStatut(etat: UpdateState, installee: String): String = when (etat) {
        UpdateState.Idle -> "Version installée : v$installee"
        UpdateState.Checking -> "Vérification en cours…"
        is UpdateState.UpToDate -> "À jour (v${etat.version})"
        is UpdateState.Available -> "Mise à jour disponible : v${etat.version}"
        is UpdateState.Downloading -> "Téléchargement de v${etat.version} : ${(etat.progress * 100).toInt()} %"
        is UpdateState.ReadyToInstall -> "v${etat.version} téléchargée, prête à installer"
        is UpdateState.Failed ->
            if (etat.pendantTelechargement) {
                "Téléchargement impossible : ${etat.detail ?: "erreur inconnue"}"
            } else {
                "Vérification impossible : ${etat.detail ?: "erreur inconnue"}. " +
                    "Ce n'est pas un « à jour », réessaie plus tard."
            }
    }

    /** Version portée par le nom d'un APK téléchargé (vigie-reseau-v1.2.3.apk). */
    fun versionDuFichier(nom: String): String? = UpdateChecker.extractVersion(nom.removeSuffix(".apk"))

    /** Un APK déjà téléchargé dont la version n'est plus plus récente que l'installée est à purger. */
    fun apkPerime(nom: String, installee: String): Boolean {
        val version = versionDuFichier(nom) ?: return true
        return UpdateChecker.compareVersions(version, installee) <= 0
    }
}
