# Vigie Réseau

Passerelle Wi-Fi surveillée depuis le téléphone : le mobile reste client d'un
Wi-Fi, partage sa connexion, et l'app montre **qui est connecté, ce qu'il
consomme et quels domaines il visite**.

## Pourquoi le root

Android n'accorde à aucune application l'accès aux appareils du partage de
connexion : pas de socket brut, pas de capture de paquets, et le `VpnService`
ne voit que le trafic des apps du téléphone. Tout ce qui compte ici passe donc
par `su` :

- **baux DHCP** (`/data/misc/dhcp/dnsmasq.leases`) : IP, MAC et nom des appareils ;
- **voisins** (`ip neigh`) : qui est réellement en ligne ;
- **compteurs** (chaînes `iptables` dédiées) : octets émis et reçus par appareil ;
- **questions DNS** : une redirection `iptables` envoie les requêtes des clients
  vers le relais de l'app, qui note le domaine demandé puis transmet au vrai
  résolveur. Aucun certificat, aucune interception TLS : on voit les domaines,
  pas le contenu des pages.

Sans root, l'app affiche seulement l'état du partage de connexion et le explique.

Le root n'est demandé **qu'une fois** : un seul shell `su` est ouvert et toutes
les commandes passent dedans. Un `su -c` par commande faisait apparaître une
notification Magisk toutes les trois secondes.

### Redirections laissées par une session tuée

Si l'app est tuée pendant la surveillance, la règle `iptables` reste en place et
enverrait le DNS des appareils vers un port muet. Au démarrage suivant, l'app
liste `PREROUTING`, retire les redirections qui visent un de ses ports et note
l'opération dans le journal.

### Port du relais DNS

Android réserve le port UDP 5353 à son service mDNS (`mdnsd`). Le relais essaie
donc 35353, 45353, 53535, 15353, 5533 puis 5353, retient le premier libre,
l'utilise dans la règle `iptables` et relit la chaîne `PREROUTING` pour vérifier
que la redirection est bien en place. Si aucun port ne répond, la surveillance
démarre quand même (appareils et volumes) et l'app explique pourquoi les
domaines ne remontent pas.

## Écrans

- **Passerelle** : root accordé ou non, hotspot et uplink détectés, sous-réseau,
  nombre d'appareils, volume total, requêtes DNS observées, bouton démarrer /
  arrêter, export CSV.
- **Appareils** : IP, MAC, nom DHCP, volume, nombre de requêtes ; un appui ouvre
  le détail avec les domaines les plus demandés.
- **Requêtes** : les domaines demandés regroupés **par appareil** (nom de
  l'appareil puis son IP, nombre d'appels et heure du dernier appel par domaine),
  avec un filtre de domaine. Un appui sur un appareil déplie sa liste ; c'est la
  vue qui montre ce que fait chaque appareil connecté. L'historique est écrit en
  base locale (SQLite, 7 jours de rétention) et rechargé au lancement : il
  survit au redémarrage de l'app et l'export CSV le reprend en entier.
- **Journal** : détections d'appareils, démarrages, domaines surveillés.

## Réglage

Un **domaine à surveiller** (par exemple `tiktok`) fait remonter en alerte chaque
requête correspondante, dans le journal.

## Limites assumées

- Ce qui est chiffré reste chiffré : l'app voit les noms de domaines, pas les URL
  ni le contenu.
- Le relais DNS suppose que les clients utilisent le DNS du hotspot ; un appareil
  configuré en DNS chiffré (DoH) échappe à l'observation.
- L'app dépend du root : après une mise à jour système, il faut réaccorder
  l'accès dans Magisk.

## Build

```
ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest assembleRelease --no-daemon
```

Signature : `VIGIERESEAU_KEYSTORE_*` en CI, fichiers locaux dans
`/root/.secrets/keystores-android/` sur ARGOS.
