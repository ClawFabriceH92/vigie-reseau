package com.trucdecomptable.vigieresau.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trucdecomptable.vigieresau.data.Appareil
import com.trucdecomptable.vigieresau.data.EtatPasserelle
import com.trucdecomptable.vigieresau.data.Evenement
import com.trucdecomptable.vigieresau.data.RequeteDns
import com.trucdecomptable.vigieresau.data.octetsLisibles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Trois onglets : l'état de la passerelle, les appareils, le journal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Racine(vm: ReseauViewModel = viewModel()) {
    val context = LocalContext.current
    val etat by vm.etat.collectAsStateWithLifecycle()
    val appareils by vm.appareils.collectAsStateWithLifecycle()
    val requetes by vm.requetes.collectAsStateWithLifecycle()
    val journal by vm.journal.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var onglet by remember { mutableStateOf(0) }
    var reglages by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Appareil?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.rafraichirEtat() }

    LaunchedEffect(message) {
        val texte = message ?: return@LaunchedEffect
        snackbar.showSnackbar(texte)
        vm.messageLu()
    }

    fun partagerCsv() {
        val fichier = vm.exporterCsv()
        if (fichier == null) {
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fichier)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Vigie Réseau · export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Envoyer l'export"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titres[onglet]) },
                actions = {
                    if (onglet == 0) {
                        IconButton(onClick = { vm.rafraichirEtat(); vm.rafraichirDonnees() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rafraîchir")
                        }
                        IconButton(onClick = { reglages = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Réglages")
                        }
                        IconButton(onClick = { partagerCsv() }) {
                            Icon(Icons.Default.IosShare, contentDescription = "Exporter en CSV")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = onglet == 0,
                    onClick = { onglet = 0 },
                    icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                    label = { Text("Passerelle") },
                )
                NavigationBarItem(
                    selected = onglet == 1,
                    onClick = { onglet = 1 },
                    icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                    label = { Text("Appareils (${appareils.size})") },
                )
                NavigationBarItem(
                    selected = onglet == 2,
                    onClick = { onglet = 2 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Journal") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (onglet) {
                0 -> OngletPasserelle(
                    etat = etat,
                    totalAppareils = appareils.size,
                    volume = appareils.sumOf { it.total },
                    domaines = requetes.size,
                    onDemarrer = vm::demarrer,
                    onArreter = vm::arreter,
                )

                1 -> OngletAppareils(appareils = appareils, onOuvrir = { detail = it })

                else -> OngletJournal(journal = journal, onVider = vm::oublierJournal)
            }
        }
    }

    if (reglages) {
        DialogueDomaine(
            initial = etat.domaineSurveille,
            onAnnuler = { reglages = false },
            onValider = { domaine ->
                vm.definirDomaineSurveille(domaine)
                reglages = false
            },
        )
    }

    detail?.let { appareil ->
        DialogueAppareil(
            appareil = appareil,
            domaines = requetes.filter { it.ipSource == appareil.ip }
                .groupingBy { it.domaine }.eachCount()
                .entries.sortedByDescending { it.value }
                .take(30),
            onFermer = { detail = null },
        )
    }
}

private val titres = listOf("Vigie Réseau", "Appareils", "Journal")

@Composable
private fun OngletPasserelle(
    etat: EtatPasserelle,
    totalAppareils: Int,
    volume: Long,
    domaines: Int,
    onDemarrer: () -> Unit,
    onArreter: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(etat.resume, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Ligne("Root", if (etat.racine) "accordé" else "absent")
                Ligne("Hotspot", etat.interfaceHotspot ?: "inactif")
                Ligne("Sous-réseau", etat.sousReseau ?: "—")
                Ligne("Uplink", etat.uplink ?: "—")
                Ligne("Surveillance", if (etat.surveillance) "active" else "arrêtée")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$totalAppareils appareil(s) · ${octetsLisibles(volume)}", style = MaterialTheme.typography.titleMedium)
                Text("$domaines requête(s) DNS observée(s)", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (etat.surveillance) {
            OutlinedButton(onClick = onArreter, modifier = Modifier.fillMaxWidth()) {
                Text("Arrêter la surveillance")
            }
        } else {
            Button(onClick = onDemarrer, modifier = Modifier.fillMaxWidth()) {
                Text("Démarrer la surveillance")
            }
        }

        Text(
            "La surveillance lit les baux DHCP, les voisins, les compteurs iptables et les " +
                "questions DNS des appareils connectés. Sans root, ces informations ne sont " +
                "pas accessibles : l'app ne montre alors que l'état du partage de connexion.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Ligne(libelle: String, valeur: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(libelle, style = MaterialTheme.typography.bodyMedium)
        Text(valeur, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OngletAppareils(appareils: List<Appareil>, onOuvrir: (Appareil) -> Unit) {
    if (appareils.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                "Aucun appareil vu pour l'instant. Active le partage de connexion, démarre la " +
                    "surveillance, puis connecte un appareil au hotspot.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(appareils, key = { it.ip }) { appareil ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { onOuvrir(appareil) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(appareil.libelle, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${appareil.ip}${if (appareil.mac.isNotBlank()) " · ${appareil.mac}" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (appareil.requetes > 0) {
                            Text("${appareil.requetes} requête(s) DNS", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(octetsLisibles(appareil.total), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun OngletJournal(journal: List<Evenement>, onVider: () -> Unit) {
    if (journal.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Aucun événement.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val format = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE) }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onVider, modifier = Modifier.align(Alignment.End)) { Text("Vider") }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(journal) { evenement ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            format.format(Date(evenement.horodatage)),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            evenement.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (evenement.alerte) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogueDomaine(initial: String, onAnnuler: () -> Unit, onValider: (String) -> Unit) {
    var texte by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Domaine à surveiller") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Chaque requête vers un domaine contenant ce texte remonte en alerte dans le journal.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = texte,
                    onValueChange = { texte = it },
                    label = { Text("ex. tiktok") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onValider(texte) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

@Composable
private fun DialogueAppareil(
    appareil: Appareil,
    domaines: List<Map.Entry<String, Int>>,
    onFermer: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(appareil.libelle) },
        text = {
            Column(Modifier.height(320.dp)) {
                Text(appareil.ip, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Émis ${octetsLisibles(appareil.octetsEmis)} · reçu ${octetsLisibles(appareil.octetsRecus)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Domaines demandés", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                if (domaines.isEmpty()) {
                    Text("Aucune requête DNS enregistrée.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn {
                        items(domaines) { entree ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(entree.key, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text("${entree.value}×", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onFermer) { Text("Fermer") } },
    )
}
