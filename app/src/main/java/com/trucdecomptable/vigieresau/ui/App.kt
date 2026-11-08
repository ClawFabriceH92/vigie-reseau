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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trucdecomptable.vigieresau.data.Appareil
import com.trucdecomptable.vigieresau.data.EtatPasserelle
import com.trucdecomptable.vigieresau.data.Evenement
import com.trucdecomptable.vigieresau.data.RequeteDns
import com.trucdecomptable.vigieresau.data.RequetesAppareil
import com.trucdecomptable.vigieresau.data.octetsLisibles
import com.trucdecomptable.vigieresau.data.regrouperParAppareil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Quatre onglets : la passerelle, les appareils, les requêtes par appareil, le journal. */
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

    // Au premier affichage comme à chaque retour au premier plan : l'état vieillit
    // dès que l'utilisateur active le partage de connexion hors de l'app.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.rafraichirEtat() }

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
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Requêtes (${requetes.size})") },
                )
                NavigationBarItem(
                    selected = onglet == 3,
                    onClick = { onglet = 3 },
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
                    onChoisirInterface = vm::definirInterfaceManuelle,
                )

                1 -> OngletAppareils(appareils = appareils, onOuvrir = { detail = it })

                2 -> OngletRequetes(appareils = appareils, requetes = requetes)

                else -> OngletJournal(journal = journal, onVider = vm::oublierJournal)
            }
        }
    }

    // Dialogue de mise à jour : visible quel que soit l'onglet ouvert.
    DialogueMiseAJour()

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

private val titres = listOf("Vigie Réseau", "Appareils", "Requêtes", "Journal")

@Composable
private fun OngletPasserelle(
    etat: EtatPasserelle,
    totalAppareils: Int,
    volume: Long,
    domaines: Int,
    onDemarrer: () -> Unit,
    onArreter: () -> Unit,
    onChoisirInterface: (String?) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                Ligne(
                    "Observation DNS",
                    when {
                        etat.dnsActif -> etat.dnsInfo ?: "active"
                        etat.surveillance -> "inactive"
                        else -> "—"
                    },
                )
            }
        }

        if (etat.surveillance && !etat.dnsActif) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Appareils et volumes mesurés, domaines non observés", fontWeight = FontWeight.SemiBold)
                    etat.dnsInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
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

        SectionDiagnostic(etat = etat, onChoisir = onChoisirInterface)

        Text(
            "La surveillance lit les baux DHCP, les voisins, les compteurs iptables et les " +
                "questions DNS des appareils connectés. Sans root, ces informations ne sont " +
                "pas accessibles : l'app ne montre alors que l'état du partage de connexion.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Diagnostic : toutes les interfaces lues, celle retenue pour le partage et la
 * raison, plus le choix manuel (mémorisé, prioritaire sur la détection).
 */
@Composable
private fun SectionDiagnostic(etat: EtatPasserelle, onChoisir: (String?) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Diagnostic", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Ligne("Interface retenue", etat.interfaceHotspot ?: "aucune")
            Ligne("Source", if (etat.interfaceHotspot == null) "aucune" else etat.source.libelle)
            Ligne("Désignée par dumpsys", etat.designeesDumpsys.joinToString(", ").ifBlank { "rien" })
            if (etat.choixManuel != null) Ligne("Choix manuel", etat.choixManuel)

            Text(
                "Interfaces vues (${etat.interfacesVues.size})",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (etat.interfacesVues.isEmpty()) {
                Text(
                    if (etat.racine) "Aucune interface lue." else "Aucune interface lue (root absent).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            etat.interfacesVues.forEach { vue ->
                val notes = listOfNotNull(
                    if (vue.nom == etat.interfaceHotspot) "retenue" else null,
                    if (vue.nom == etat.uplink) "uplink" else null,
                    if (vue.nom == etat.choixManuel) "choisie" else null,
                ).joinToString(" · ")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            vue.nom,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (vue.nom == etat.interfaceHotspot) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            "${vue.ip}/${vue.prefixe}" + if (notes.isNotBlank()) " · $notes" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        onClick = { onChoisir(vue.nom) },
                        enabled = !etat.surveillance && vue.nom != etat.choixManuel,
                    ) { Text("Choisir") }
                }
            }

            OutlinedButton(
                onClick = { onChoisir(null) },
                enabled = !etat.surveillance && etat.choixManuel != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Détection automatique") }
            if (etat.surveillance) {
                Text("Arrête la surveillance pour changer d'interface.", style = MaterialTheme.typography.bodySmall)
            }
        }
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

/**
 * Requêtes DNS regroupées par appareil : c'est la vue qui montre ce que chaque
 * appareil du partage demande réellement, domaine par domaine.
 */
@Composable
private fun OngletRequetes(appareils: List<Appareil>, requetes: List<RequeteDns>) {
    var filtre by remember { mutableStateOf("") }
    var ouvert by remember { mutableStateOf<String?>(null) }
    val groupes = remember(requetes, filtre) { regrouperParAppareil(requetes, filtre) }
    val noms = remember(appareils) { appareils.associate { it.ip to it.libelle } }
    val format = remember { SimpleDateFormat("HH:mm:ss", Locale.FRANCE) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filtre,
            onValueChange = { filtre = it },
            label = { Text("Filtrer un domaine") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (groupes.isEmpty()) {
            Text(
                if (requetes.isEmpty()) {
                    "Aucune requête DNS observée. Démarre la surveillance : les domaines demandés " +
                        "par les appareils du partage apparaîtront ici, appareil par appareil."
                } else {
                    "Aucun domaine ne correspond à « $filtre »."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(groupes, key = { it.ip }) { groupe ->
                CarteRequetes(
                    groupe = groupe,
                    libelle = noms[groupe.ip],
                    deplie = ouvert == groupe.ip,
                    format = format,
                    onBasculer = { ouvert = if (ouvert == groupe.ip) null else groupe.ip },
                )
            }
        }
    }
}

/** Un appareil, son volume de requêtes, et ses domaines quand on le déplie. */
@Composable
private fun CarteRequetes(
    groupe: RequetesAppareil,
    libelle: String?,
    deplie: Boolean,
    format: SimpleDateFormat,
    onBasculer: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onBasculer).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        libelle?.takeIf { it.isNotBlank() } ?: groupe.ip,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${groupe.total} requête(s) · ${groupe.domaines.size} domaine(s) · " +
                            "dernière à ${format.format(Date(groupe.dernier))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(if (deplie) "\u25BE" else "\u25B8", style = MaterialTheme.typography.titleMedium)
            }
            if (deplie) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                groupe.domaines.take(MAX_DOMAINES_AFFICHES).forEach { domaine ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            domaine.domaine,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${domaine.occurrences}\u00D7 · ${format.format(Date(domaine.dernier))}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (groupe.domaines.size > MAX_DOMAINES_AFFICHES) {
                    Text(
                        "… et ${groupe.domaines.size - MAX_DOMAINES_AFFICHES} autre(s). " +
                            "Utilise le filtre pour affiner.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** Nombre de domaines dépliés d'un coup : au-delà, le filtre prend le relais. */
private const val MAX_DOMAINES_AFFICHES = 300

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
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SectionMiseAJour()
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
