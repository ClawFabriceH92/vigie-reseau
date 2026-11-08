package com.trucdecomptable.vigieresau.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Relais DNS qui note les questions des appareils du partage de connexion.
 *
 * Il ne remplace pas le résolveur du téléphone : une règle iptables redirige
 * les requêtes des clients (port 53) vers ce relais, qui enregistre le nom
 * demandé puis transmet la question au vrai résolveur. On obtient ainsi, sans
 * certificat ni interception TLS, la liste des domaines visités par appareil.
 */
class RelaisDns(
    private val port: Int = PORT,
    private val surRequete: (ipSource: String, domaine: String) -> Unit,
) {

    private var socket: DatagramSocket? = null

    /** Raison du dernier échec de [demarrer], pour l'afficher à l'utilisateur. */
    @Volatile
    var derniereErreur: String? = null
        private set

    /** Port réellement écouté, null tant que le relais n'a pas démarré. */
    @Volatile
    var portUtilise: Int? = null
        private set

    /** Ports essayés lors du dernier démarrage, dans l'ordre. */
    @Volatile
    var portsTentes: List<Int> = emptyList()
        private set
    private val actif = AtomicBoolean(false)
    private val fil = Executors.newSingleThreadExecutor { t -> Thread(t, "vigie-dns-relais").apply { isDaemon = true } }
    private val reponses = Executors.newFixedThreadPool(4) { t ->
        Thread(t, "vigie-dns-reponse").apply { isDaemon = true }
    }

    /**
     * Démarre l'écoute. [amont] = résolveurs réels du téléphone.
     *
     * Le port est cherché parmi [port], puis [PORT_CANDIDATS] : Android occupe
     * déjà 5353 avec son service mDNS, donc un port fixe échoue sur beaucoup de
     * téléphones. Le port retenu est publié dans [portUtilise] et sert à la
     * règle de redirection.
     */
    fun demarrer(amont: List<InetAddress>, candidats: List<Int> = PORT_CANDIDATS): Boolean {
        if (actif.get()) return true
        derniereErreur = null
        val tentes = mutableListOf<Int>()
        for (essaie in (listOf(port) + candidats).distinct()) {
            tentes += essaie
            val s = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(essaie))
                }
            } catch (e: Exception) {
                derniereErreur = "port $essaie : " + (e.message ?: e.javaClass.simpleName)
                continue
            }
            socket = s
            portUtilise = essaie
            portsTentes = tentes.toList()
            actif.set(true)
            fil.execute { boucle(s, amont) }
            return true
        }
        actif.set(false)
        portUtilise = null
        portsTentes = tentes.toList()
        return false
    }

    fun arreter() {
        actif.set(false)
        runCatching { socket?.close() }
        socket = null
    }

    private fun boucle(s: DatagramSocket, amont: List<InetAddress>) {
        val tampon = ByteArray(1500)
        while (actif.get()) {
            val paquet = DatagramPacket(tampon, tampon.size)
            try {
                s.receive(paquet)
            } catch (e: Exception) {
                return
            }
            val donnees = paquet.data.copyOfRange(paquet.offset, paquet.offset + paquet.length)
            val source = paquet.address?.hostAddress.orEmpty()
            val domaine = Dns.nomDemande(donnees)
            if (!domaine.isNullOrBlank()) surRequete(source, domaine)
            reponses.execute { transferer(s, donnees, paquet.address, paquet.port, amont) }
        }
    }

    private fun transferer(
        relais: DatagramSocket,
        requete: ByteArray,
        client: InetAddress,
        portClient: Int,
        amont: List<InetAddress>,
    ) {
        for (serveur in amont) {
            try {
                DatagramSocket().use { sortie ->
                    sortie.soTimeout = 3000
                    sortie.send(DatagramPacket(requete, requete.size, serveur, 53))
                    val tampon = ByteArray(1500)
                    val reponse = DatagramPacket(tampon, tampon.size)
                    sortie.receive(reponse)
                    val donnees = reponse.data.copyOfRange(reponse.offset, reponse.offset + reponse.length)
                    relais.send(DatagramPacket(donnees, donnees.size, client, portClient))
                    return
                }
            } catch (e: Exception) {
                // Serveur suivant.
            }
        }
    }

    companion object {
        /** Port non privilégié : la redirection iptables fait la traduction. */
        const val PORT = 35353

        /**
         * Ports essayés dans l'ordre. 5353 est laissé en dernier parce que le
         * service mDNS d'Android (mdnsd) l'occupe en permanence, ce qui faisait
         * échouer le démarrage de la surveillance sur le téléphone de test.
         */
        val PORT_CANDIDATS = listOf(35353, 45353, 53535, 15353, 5533, 5353)

        /**
         * Redirige les requêtes DNS des clients du hotspot vers le relais.
         * La règle est supprimée par [desinstaller].
         */
        fun installer(interfaceHotspot: String, port: Int = PORT): Boolean {
            val commande = "iptables -t nat -C PREROUTING -i $interfaceHotspot -p udp --dport 53 " +
                "-j REDIRECT --to-ports $port 2>/dev/null || " +
                "iptables -t nat -A PREROUTING -i $interfaceHotspot -p udp --dport 53 " +
                "-j REDIRECT --to-ports $port"
            if (Racine.exec(commande) == null) return false
            // La commande réussit aussi quand la règle existait déjà : on relit
            // la chaîne pour être certain que la redirection est en place.
            val chaine = Racine.exec("iptables -t nat -S PREROUTING").orEmpty()
            return Reseau.regleRedirectionPresente(chaine, interfaceHotspot, port)
        }

        fun desinstaller(interfaceHotspot: String, port: Int = PORT) {
            Racine.exec(
                "iptables -t nat -D PREROUTING -i $interfaceHotspot -p udp --dport 53 " +
                    "-j REDIRECT --to-ports $port 2>/dev/null",
            )
        }
    }
}
