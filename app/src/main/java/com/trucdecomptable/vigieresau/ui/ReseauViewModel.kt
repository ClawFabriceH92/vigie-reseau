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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var boucle: Job? = null
    private val vus = mutableSetOf<String>()

    fun messageLu() {
        _message.value = null
    }

    /** Détection seule : pas besoin de root pour l'état, seulement pour les détails. */
    fun rafraichirEtat() {
        viewModelScope.launch(Dispatchers.IO) {
            _etat.value = detecter(_etat.value.surveillance)
        }
    }

    private fun detecter(surveillance: Boolean): EtatPasserelle {
        val racine = Racine.disponible()
        val adresses = Racine.exec("ip -o -4 addr show").orEmpty()
        val route = Racine.exec("ip route get 1.1.1.1").orEmpty()
        val interfaces = Reseau.interfaces(adresses)
        val uplink = Reseau.uplink(route)
        val hotspot = Reseau.interfaceHotspot(interfaces, uplink)
        return EtatPasserelle(
            racine = racine,
            hotspotActif = hotspot != null,
            interfaceHotspot = hotspot?.nom,
            sousReseau = hotspot?.let { Reseau.sousReseau(it.ip, it.prefixe) },
            uplink = uplink,
            surveillance = surveillance,
            domaineSurveille = _etat.value.domaineSurveille,
        )
    }

    // ------------------------------------------------------------- surveillance

    fun demarrer() {
        val courant = _etat.value
        if (!courant.racine) {
            _message.value = "Root indisponible : accorde l'accès à l'app dans Magisk"
            return
        }
        val iface = courant.interfaceHotspot
        if (iface == null) {
            _message.value = "Active d'abord le partage de connexion"
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val compteurs = Compteurs.preparer()
                val redirection = RelaisDns.installer(iface)
                val relaisPret = RelaisDns(surRequete = ::noterRequete).also { relais = it }.demarrer(amontDns())
                journaliser("Surveillance démarrée sur $iface (compteurs=${if (compteurs) "ok" else "ko"}, " +
                    "dns=${if (relaisPret) "ok" else "ko"}, redirection=${if (redirection) "ok" else "ko"})")
                relaisPret
            }
            _etat.value = _etat.value.copy(surveillance = true)
            if (ok) {
                _message.value = "Surveillance active"
                lancerBoucle()
            } else {
                _message.value = "Impossible de démarrer : port 5353 occupé ?"
                _etat.value = _etat.value.copy(surveillance = false)
            }
        }
    }

    fun arreter() {
        boucle?.cancel()
        boucle = null
        val iface = _etat.value.interfaceHotspot
        viewModelScope.launch(Dispatchers.IO) {
            relais?.arreter()
            relais = null
            if (iface != null) RelaisDns.desinstaller(iface)
            Compteurs.arreter()
            journaliser("Surveillance arrêtée")
            _etat.value = _etat.value.copy(surveillance = false)
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
            val courant = detecter(_etat.value.surveillance)
            _etat.value = courant
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
    }

    companion object {
        /** Bornes de rétention : l'app doit tenir des heures sans saturer la RAM. */
        const val RETENTION_REQUETES = 5_000
    }
}
