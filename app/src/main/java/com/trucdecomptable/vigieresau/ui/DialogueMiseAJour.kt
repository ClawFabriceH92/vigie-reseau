package com.trucdecomptable.vigieresau.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trucdecomptable.vigieresau.BuildConfig
import com.trucdecomptable.vigieresau.update.UpdateManager
import com.trucdecomptable.vigieresau.update.UpdateRegles
import com.trucdecomptable.vigieresau.update.UpdateState

/**
 * Dialogue de mise à jour, posé au-dessus de la racine : il s'ouvre quel que soit
 * l'écran affiché. Boutons : Télécharger, Installer, Autoriser, Plus tard.
 */
@Composable
fun DialogueMiseAJour() {
    val context = LocalContext.current
    val etat by UpdateManager.state.collectAsStateWithLifecycle()
    val reportee by UpdateManager.reportee.collectAsStateWithLifecycle()

    // L'autorisation d'installer se donne dans les réglages système : on la relit au retour.
    var relecture by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { relecture++ }
    val peutInstaller = remember(relecture) { UpdateManager.canInstall(context) }

    if (!UpdateRegles.dialogueVisible(etat, reportee)) return

    when (val e = etat) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = UpdateManager::plusTard,
            title = { Text("Mise à jour disponible") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Vigie Réseau v${e.version} est disponible (installée : v${BuildConfig.VERSION_NAME}).")
                    if (e.notes.isNotBlank()) {
                        Text(e.notes.take(400), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { UpdateManager.download(context) }) { Text("Télécharger") } },
            dismissButton = { TextButton(onClick = UpdateManager::plusTard) { Text("Plus tard") } },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = UpdateManager::plusTard,
            title = { Text("Téléchargement de v${e.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (e.progress > 0f) {
                        LinearProgressIndicator(progress = { e.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(e.progress * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = UpdateManager::plusTard) { Text("Plus tard") } },
        )

        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = UpdateManager::plusTard,
            title = { Text("Mise à jour prête") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Vigie Réseau v${e.version} est téléchargée et peut être installée par-dessus la version actuelle.")
                    if (!peutInstaller) {
                        Text(
                            "Android doit d'abord autoriser Vigie Réseau à installer des applications. " +
                                "Touche Autoriser, active l'option, puis reviens ici.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                if (peutInstaller) {
                    TextButton(onClick = { UpdateManager.install(context) }) { Text("Installer") }
                } else {
                    TextButton(onClick = { UpdateManager.openInstallSettings(context) }) { Text("Autoriser") }
                }
            },
            dismissButton = { TextButton(onClick = UpdateManager::plusTard) { Text("Plus tard") } },
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = UpdateManager::plusTard,
            title = { Text("Téléchargement impossible") },
            text = { Text(e.detail ?: "Erreur inconnue.") },
            confirmButton = { TextButton(onClick = { UpdateManager.checkNow(context) }) { Text("Réessayer") } },
            dismissButton = { TextButton(onClick = UpdateManager::plusTard) { Text("Fermer") } },
        )

        else -> Unit
    }
}

/** Bloc des réglages : statut lisible et bouton « Vérifier maintenant ». */
@Composable
fun SectionMiseAJour() {
    val context = LocalContext.current
    val etat by UpdateManager.state.collectAsStateWithLifecycle()
    val enCours = etat is UpdateState.Checking || etat is UpdateState.Downloading
    val erreur = etat is UpdateState.Failed && !(etat as UpdateState.Failed).pendantTelechargement
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Mise à jour", style = MaterialTheme.typography.titleSmall)
        Text(
            UpdateRegles.libelleStatut(etat, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = if (erreur) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        OutlinedButton(
            onClick = { UpdateManager.checkNow(context) },
            enabled = !enCours,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Vérifier maintenant") }
    }
}
