package com.trucdecomptable.vigieresau.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trucdecomptable.vigieresau.data.Appareil
import com.trucdecomptable.vigieresau.data.EtatPasserelle
import com.trucdecomptable.vigieresau.data.Evenement
import com.trucdecomptable.vigieresau.data.RequeteDns
import com.trucdecomptable.vigieresau.net.Compteurs
import com.trucdecomptable.vigieresau.net.Racine
import com.trucdecomptable.vigieresau.net.RelaisDns
import com.trucdecomptable.vigieresau.net.Reseau
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tout l'état de l'app : détection de la passerelle, appareils, domaines vus,
 * journal. Le rafraîchissement tourne toutes les 3 s pendant la surveillance.
 */
class ReseauViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = application.getSharedPreferences("vigie", Context.MODE_PRIVATE)

    private val _etat = MutableStateFlow(EtatPasserelle(domaineSurveille = preferences.getString("domaine", "").orEmpty()))
    val etat: StateFlow<EtatPasserelle> = _etat.asStateFlow()

    private val _appareils = MutableStateFlow<List<Appareil>>(emptyList())
    val appareils: StateFlow<List<Appareil>> = _appareils.asStateFlow()

    private val _requetes = MutableStateFlow<List<RequeteDns>>(emptyList())
    val requetes: StateFlow<List<RequeteDns>> = _requetes.asStateFlow()

    private val _journal = MutableStateFlow<List<Evenement>>(emptyList())
    val journal: StateFlow<List<Evenement>> = _journal.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var relais: RelaisDns? = null
    private var interfaceSurveillee: String? = null
    /** Port réellement écouté par le relais, utile pour retirer la redirection. */
    private var portRelais: Int? = null
    private var erreurRelais: String? = null
    private val demarrageEnCours = AtomicBoolean(false)
    private var dumpsysCache = ""
    private var dumpsysHeure = 0L
    private var boucle: Job? = null
    private val vus = mutableSetOf<String>()

    fun messageLu() {
        _message.value = null
    }

    /** Détection seule : pas besoin de root pour l'état, seulement pour les détails. */
    fun rafraichirEtat() {
        viewModelScope.launch(Dispatchers.IO) {
            fusionner(detecter(forcerDumpsys = false))
        }
    }

    /**
     * Range une détection fraîche dans l'état sans écraser ce qui n'est pas de
     * son ressort (surveillance en cours, domaine surveillé) : la valeur est lue
     * au moment de l'écriture, pas avant la détection.
     */
    private fun fusionner(detection: EtatPasserelle) {
        _etat.update { it.copy(
            racine = detection.racine,
            hotspotActif = detection.hotspotActif,
            interfaceHotspot = detection.interfaceHotspot,
            sousReseau = detection.sousReseau,
            uplink = detection.uplink,
            interfacesVues = detection.interfacesVues,
            designeesDumpsys = detection.designeesDumpsys,
            source = detection.source,
            choixManuel = detection.choixManuel,
        ) }
    }

    /** Lit le téléphone puis laisse [Reseau.construireEtat] décider. À appeler hors du fil principal. */
    private fun detecter(forcerDumpsys: Boolean): EtatPasserelle {
        val racine = Racine.disponible()
        val manuelle = preferences.getString(CLE_INTERFACE_MANUELLE, null)?.takeIf { it.isNotBlank() }
        var interfaces = if (racine) Reseau.interfaces(Racine.exec("ip -o -4 addr show").orEmpty()) else emptyList()
        // Secours sans `ip` : l'API Java voit les interfaces actives, même sans root.
        if (interfaces.isEmpty()) interfaces = interfacesJava()
        val uplink = (if (racine) Reseau.uplink(Racine.exec("ip route get 1.1.1.1").orEmpty()) else null)
            ?: uplinkAndroid()
        // dumpsys est lourd et inutile si l'utilisateur a imposé son interface.
        val dumpsys = if (racine && manuelle == null) lireDumpsys(forcerDumpsys) else ""
        return Reseau.construireEtat(racine, interfaces, uplink, dumpsys, manuelle)
    }

    /** Sortie de `dumpsys` (tethering puis wifi), mise en cache 30 s pour la boucle de 3 s. */
    private fun lireDumpsys(forcer: Boolean): String {
        val maintenant = System.currentTimeMillis()
        if (!forcer && maintenant - dumpsysHeure < DUREE_CACHE_DUMPSYS_MS) return dumpsysCache
        var texte = Racine.exec("dumpsys connectivity", 10_000).orEmpty()
        if (Reseau.interfacesDesigneesDumpsys(texte).isEmpty()) {
            texte += "\n" + Racine.exec("dumpsys wifi", 10_000).orEmpty()
        }
        dumpsysCache = texte
        dumpsysHeure = maintenant
        return texte
    }

    private fun interfacesJava(): List<Reseau.InterfaceIp> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { ni ->
            ni.interfaceAddresses.filter { it.address is Inet4Address }.map {
                Reseau.InterfaceIp(ni.name, it.address.hostAddress.orEmpty(), it.networkPrefixLength.toInt())
            }
        }
    }.getOrDefault(emptyList())

    private fun uplinkAndroid(): String? {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val actif = cm?.activeNetwork ?: return null
        return cm.getLinkProperties(actif)?.interfaceName
    }

    // ------------------------------------------------------------- surveillance

    /**
     * Recalcule l'état AVANT de décider : l'écran a pu vieillir (partage activé
     * après l'ouverture de l'app, retour de l'arrière-plan).
     */
    fun demarrer() {
        if (_etat.value.surveillance || !demarrageEnCours.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val (frais, verdict) = withContext(Dispatchers.IO) {
                    Reseau.evaluerDemarrage { detecter(forcerDumpsys = true) }
                }
                fusionner(frais)
                if (verdict !is Reseau.Verdict.Autorise) {
                    val texte = Reseau.messageRefus(verdict).orEmpty()
                    journaliser("Démarrage refusé : $texte · ${Reseau.ligneDecision(frais)}", alerte = true)
                    _message.value = texte
                    return@launch
                }
                val iface = verdict.interfaceHotspot
                // Un relais DNS qui ne démarre pas ne doit plus bloquer tout le
                // reste : appareils et volumes restent mesurables sans lui.
                val resultat = withContext(Dispatchers.IO) {
                    journaliser(Reseau.ligneDecision(frais))
                    val compteurs = Compteurs.preparer()
                    val nouveau = RelaisDns(surRequete = ::noterRequete)
                    val relaisPret = nouveau.demarrer(amontDns())
                    var dnsOk = false
                    var raison: String? = null
                    if (!relaisPret) {
                        raison = Reseau.messageDnsIndisponible(nouveau.portsTentes, nouveau.derniereErreur)
                        erreurRelais = nouveau.derniereErreur
                    } else {
                        val port = nouveau.portUtilise
                        if (port != null && RelaisDns.installer(iface, port)) {
                            relais = nouveau
                            portRelais = port
                            interfaceSurveillee = iface
                            dnsOk = true
                        } else {
                            // Pas de redirection valable : on ne laisse pas le
                            // relais tourner dans le vide.
                            nouveau.arreter()
                            raison = Reseau.messageDnsIndisponible(
                                listOfNotNull(nouveau.portUtilise),
                                "la redirection iptables a échoué",
                            )
                        }
                    }
                    val demarre = compteurs || dnsOk
                    journaliser("Surveillance " + (if (demarre) "démarrée" else "non démarrée") +
                        " sur $iface (compteurs=${if (compteurs) "ok" else "ko"}, " +
                        "dns=${if (dnsOk) "ok port " + portRelais else "ko"}, " +
                        "raison=${raison ?: "aucune"})",
                        alerte = !dnsOk)
                    Triple(demarre, dnsOk, raison)
                }
                val (demarre, dnsOk, raison) = resultat
                if (demarre) {
                    _etat.update { it.copy(surveillance = true, dnsActif = dnsOk, dnsInfo = raison ?: portRelais?.let { "port $it" }) }
                    _message.value = when {
                        dnsOk -> "Surveillance active · domaines observés sur le port $portRelais"
                        else -> raison ?: "Surveillance active sans observation DNS"
                    }
                    lancerBoucle()
                } else {
                    _etat.update { it.copy(surveillance = false, dnsActif = false, dnsInfo = raison) }
                    _message.value = raison ?: Reseau.messagePortOccupe(RelaisDns.PORT, erreurRelais)
                }
            } finally {
                demarrageEnCours.set(false)
            }
        }
    }

    fun arreter() {
        boucle?.cancel()
        boucle = null
        // L'interface où la redirection a été posée, pas celle détectée à présent.
        val iface = interfaceSurveillee ?: _etat.value.interfaceHotspot
        val port = portRelais
        viewModelScope.launch(Dispatchers.IO) {
            relais?.arreter()
            relais = null
            interfaceSurveillee = null
            portRelais = null
            if (iface != null) {
                if (port != null) RelaisDns.desinstaller(iface, port) else RelaisDns.desinstaller(iface)
            }
            Compteurs.arreter()
            journaliser("Surveillance arrêtée")
            _etat.update { it.copy(surveillance = false, dnsActif = false, dnsInfo = null) }
        }
    }

    private fun lancerBoucle() {
        boucle?.cancel()
        boucle = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                rafraichirDonnees()
                delay(3_000)
            }
        }
    }

    /** Appareils + compteurs : appelé par la boucle, réutilisable à la demande. */
    fun rafraichirDonnees() {
        viewModelScope.launch(Dispatchers.IO) {
            val courant = detecter(forcerDumpsys = false)
            fusionner(courant)
            val iface = courant.interfaceHotspot ?: return@launch

            val baux = Racine.exec("cat /data/misc/dhcp/dnsmasq.leases 2>/dev/null").orEmpty()
                .let { Reseau.baux(it) }
                .associateBy({ it.second }, { it })
            val voisins = Racine.exec("ip neigh show dev $iface").orEmpty().let { Reseau.voisins(it) }
            val compteurs = Compteurs.fusionner(
                Racine.exec("iptables -w -nvx -L ${Compteurs.CHAINE_EMIS}").orEmpty(),
                Racine.exec("iptables -w -nvx -L ${Compteurs.CHAINE_RECU}").orEmpty(),
            )
            val sousReseau = courant.sousReseau?.substringBefore('/')

            val ips = (voisins.map { it.first } + baux.keys + compteurs.keys)
                .filter { ip -> sousReseau == null || Reseau.prefixe24(ip) == sousReseau }
                .distinct()
                .sortedBy { ip -> ip.split('.').mapNotNull { it.toIntOrNull() }.fold(0) { acc, o -> acc * 256 + o } }

            val domainesParIp = _requetes.value.groupingBy { it.ipSource }.eachCount()
            val liste = ips.map { ip ->
                val mac = voisins.firstOrNull { it.first == ip }?.second
                    ?: baux[ip]?.first.orEmpty()
                val nom = baux[ip]?.third.orEmpty()
                val (emis, recu) = compteurs[ip] ?: (0L to 0L)
                Appareil(ip, mac, nom, recu, emis, domainesParIp[ip] ?: 0)
            }
            _appareils.value = liste

            // Un appareil inconnu jusqu'ici mérite une ligne d'alerte.
            liste.forEach { appareil ->
                if (vus.add(appareil.ip)) {
                    journaliser("Appareil détecté : ${appareil.libelle} (${appareil.ip})", alerte = false)
                }
            }
        }
    }

    private fun noterRequete(ipSource: String, domaine: String) {
        val requete = RequeteDns(System.currentTimeMillis(), ipSource, domaine)
        _requetes.value = (_requetes.value + requete).takeLast(RETENTION_REQUETES)
        val surveille = _etat.value.domaineSurveille
        if (surveille.isNotBlank() && domaine.contains(surveille, ignoreCase = true)) {
            journaliser("Domaine surveillé demandé : $domaine depuis $ipSource", alerte = true)
        }
    }

    private fun amontDns(): List<java.net.InetAddress> {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val actif = cm?.activeNetwork ?: return emptyList()
        return cm.getLinkProperties(actif)?.dnsServers?.filterIsInstance<Inet4Address>().orEmpty()
    }

    private fun journaliser(message: String, alerte: Boolean = false) {
        val evenement = Evenement(System.currentTimeMillis(), alerte, message)
        _journal.value = (listOf(evenement) + _journal.value).take(300)
    }

    // ------------------------------------------------------------------ réglages

    fun definirDomaineSurveille(domaine: String) {
        preferences.edit().putString("domaine", domaine.trim()).apply()
        _etat.value = _etat.value.copy(domaineSurveille = domaine.trim())
    }

    /**
     * Impose l'interface du partage (prioritaire sur toute détection) ou, avec
     * null, revient à la détection automatique. Refusé pendant la surveillance :
     * la redirection DNS est posée sur l'interface en cours.
     */
    fun definirInterfaceManuelle(nom: String?) {
        if (_etat.value.surveillance) {
            _message.value = "Arrête la surveillance avant de changer d'interface"
            return
        }
        preferences.edit().apply {
            if (nom.isNullOrBlank()) remove(CLE_INTERFACE_MANUELLE) else putString(CLE_INTERFACE_MANUELLE, nom)
        }.apply()
        journaliser(if (nom.isNullOrBlank()) "Détection automatique de l'interface rétablie" else "Interface imposée : $nom")
        rafraichirEtat()
    }

    fun oublierJournal() {
        _journal.value = emptyList()
        _requetes.value = emptyList()
        vus.clear()
    }

    // ------------------------------------------------------------------- export

    /** CSV appareils + domaines, prêt à partager. */
    fun exporterCsv(): File? = runCatching {
        val dossier = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val horodatage = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(Date())
        val fichier = File(dossier, "vigie-reseau-$horodatage.csv")
        val separateur = ";"
        val texte = buildString {
            append("type").append(separateur).append("ip").append(separateur).append("mac")
                .append(separateur).append("nom").append(separateur).append("octets_emis")
                .append(separateur).append("octets_recus").append(separateur).append("domaines")
                .append(separateur).append("horodatage").append('\n')
            _appareils.value.forEach { a ->
                append("appareil").append(separateur).append(a.ip).append(separateur).append(a.mac)
                    .append(separateur).append(a.nom).append(separateur).append(a.octetsEmis)
                    .append(separateur).append(a.octetsRecus).append(separateur).append(a.requetes)
                    .append(separateur).append("").append('\n')
            }
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
            _requetes.value.forEach { r ->
                append("dns").append(separateur).append(r.ipSource).append(separateur).append("")
                    .append(separateur).append(r.domaine).append(separateur).append("")
                    .append(separateur).append("").append(separateur).append("")
                    .append(separateur).append(format.format(Date(r.horodatage))).append('\n')
            }
        }
        fichier.writeText("\uFEFF" + texte, Charsets.UTF_8)
        fichier
    }.getOrNull()

    override fun onCleared() {
        super.onCleared()
        boucle?.cancel()
        relais?.arreter()
        // Le shell root est gardé pendant toute la vie de l'app (une seule
        // autorisation Magisk) : on le referme seulement ici.
        Racine.fermer()
    }

    companion object {
        /** Bornes de rétention : l'app doit tenir des heures sans saturer la RAM. */
        const val RETENTION_REQUETES = 5_000

        /** Clé des préférences : interface du partage choisie à la main. */
        const val CLE_INTERFACE_MANUELLE = "interface_manuelle"

        private const val DUREE_CACHE_DUMPSYS_MS = 30_000L
    }
}
