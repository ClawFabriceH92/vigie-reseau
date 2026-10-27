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
    private val actif = AtomicBoolean(false)
    private val fil = Executors.newSingleThreadExecutor { t -> Thread(t, "vigie-dns-relais").apply { isDaemon = true } }
    private val reponses = Executors.newFixedThreadPool(4) { t ->
        Thread(t, "vigie-dns-reponse").apply { isDaemon = true }
    }

    /** Démarre l'écoute. [amont] = résolveurs réels du téléphone. */
    fun demarrer(amont: List<InetAddress>): Boolean {
        if (actif.get()) return true
        return try {
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            socket = s
            actif.set(true)
            fil.execute { boucle(s, amont) }
            true
        } catch (e: Exception) {
            actif.set(false)
            false
        }
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
        const val PORT = 5353

        /**
         * Redirige les requêtes DNS des clients du hotspot vers le relais.
         * La règle est supprimée par [desinstaller].
         */
        fun installer(interfaceHotspot: String, port: Int = PORT): Boolean {
            val commande = "iptables -t nat -C PREROUTING -i $interfaceHotspot -p udp --dport 53 " +
                "-j REDIRECT --to-ports $port 2>/dev/null || " +
                "iptables -t nat -A PREROUTING -i $interfaceHotspot -p udp --dport 53 " +
                "-j REDIRECT --to-ports $port"
            return Racine.exec(commande) != null
        }

        fun desinstaller(interfaceHotspot: String, port: Int = PORT) {
            Racine.exec(
                "iptables -t nat -D PREROUTING -i $interfaceHotspot -p udp --dport 53 " +
                    "-j REDIRECT --to-ports $port 2>/dev/null",
            )
        }
    }
}
