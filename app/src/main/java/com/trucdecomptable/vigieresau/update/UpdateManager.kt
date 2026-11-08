package com.trucdecomptable.vigieresau.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.trucdecomptable.vigieresau.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Flux de mise à jour piloté depuis l'app.
 *
 * Le téléchargement est fait ici plutôt que par DownloadManager : GitHub passe
 * par un CDN derrière une chaîne de redirections que DownloadManager ne suit pas
 * toujours, ce qui produisait des fichiers tronqués refusés par l'installeur.
 * HttpURLConnection suit les redirections https, et le fichier est vérifié
 * (taille annoncée, magic bytes « PK ») avant toute installation.
 */
object UpdateManager {

    private const val TAG = "VigieUpdate"
    private const val DIR = "updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Version repoussée par « Plus tard » : le dialogue ne revient pas pour elle jusqu'à un contrôle manuel. */
    private val _reportee = MutableStateFlow<String?>(null)
    val reportee: StateFlow<String?> = _reportee.asStateFlow()

    @Volatile
    private var lastCheckAt = UpdateSchedule.NEVER

    private fun userAgent() = "VigieReseau/${BuildConfig.VERSION_NAME}"

    /** Contrôle automatique : au lancement puis toutes les 30 minutes (plancher d'une heure). */
    fun checkAuto(context: Context) {
        if (BuildConfig.DEBUG) return
        check(context.applicationContext, manual = false)
    }

    /** Bouton « Vérifier maintenant » : ignore le plancher et rouvre le dialogue si besoin. */
    fun checkNow(context: Context) = check(context.applicationContext, manual = true)

    private fun check(context: Context, manual: Boolean) {
        if (job?.isActive == true) return
        if (manual) _reportee.value = null

        val courant = _state.value
        if (UpdateRegles.miseAJourEnMain(courant)) {
            // Rien à redemander à GitHub : on reprend seulement le pré-téléchargement
            // si le réseau est devenu non facturé depuis.
            if (!manual && courant is UpdateState.Available && reseauNonFacture(context)) {
                job = scope.launch { preparerEnSilence(context, courant.version, courant.apkUrl) }
            }
            return
        }

        val now = System.currentTimeMillis()
        if (!UpdateSchedule.isDue(manual, lastCheckAt, now)) return
        lastCheckAt = now
        if (manual) _state.value = UpdateState.Checking

        job = scope.launch {
            purgerAnciens(context)
            when (val result = UpdateChecker.check(BuildConfig.VERSION_NAME, userAgent())) {
                is UpdateChecker.CheckResult.Available -> {
                    val info = result.info
                    Log.i(TAG, "version ${info.version} disponible")
                    _state.value = UpdateState.Available(info.version, info.notes, info.apkUrl)
                    preparerSiPossible(context, info.version, info.apkUrl, manual)
                }

                UpdateChecker.CheckResult.UpToDate -> {
                    Log.i(TAG, "aucune version plus récente que ${BuildConfig.VERSION_NAME}")
                    _state.value = UpdateState.UpToDate(BuildConfig.VERSION_NAME)
                }

                is UpdateChecker.CheckResult.Unreachable -> {
                    Log.w(TAG, "vérification impossible : ${result.reason}")
                    // Un contrôle raté ne garde pas le créneau horaire.
                    lastCheckAt = UpdateSchedule.NEVER
                    // Contrôle manuel : l'erreur est dite. Contrôle automatique : on
                    // ne touche pas à l'état, surtout pas pour afficher un faux « à jour ».
                    if (manual) _state.value = UpdateState.Failed(result.reason)
                    else if (_state.value == UpdateState.Checking) _state.value = UpdateState.Idle
                }
            }
        }
    }

    /**
     * Une APK déjà là passe directement à « prête à installer ». Sinon, en
     * automatique et sur réseau non facturé, elle est téléchargée sans rien demander.
     */
    private suspend fun preparerSiPossible(context: Context, version: String, url: String, manual: Boolean) {
        val dejaLa = fichierValide(context, version)
        if (dejaLa != null) {
            _state.value = UpdateState.ReadyToInstall(version, dejaLa)
            return
        }
        if (!manual && reseauNonFacture(context)) preparerEnSilence(context, version, url)
    }

    /**
     * Télécharge sans qu'on le demande, mais uniquement sur un réseau non facturé :
     * tirer l'APK en données mobiles sans prévenir n'est pas un service à rendre.
     */
    private suspend fun preparerEnSilence(context: Context, version: String, url: String) {
        if (!reseauNonFacture(context)) return
        _state.value = UpdateState.Downloading(version, 0f, silencieux = true)
        val result = runCatching { fetch(context, version, url, silencieux = true) }
        _state.value = result.fold(
            onSuccess = { UpdateState.ReadyToInstall(version, it) },
            onFailure = {
                Log.w(TAG, "pré-téléchargement raté", it)
                // Retour au bouton explicite « Télécharger », sans erreur affichée.
                UpdateState.Available(version, "", url)
            },
        )
    }

    private fun reseauNonFacture(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val reseau = cm.activeNetwork ?: return false
        val capacites = cm.getNetworkCapabilities(reseau) ?: return false
        capacites.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capacites.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    private fun nomFichier(version: String) = "vigie-reseau-v$version.apk"

    /** L'APK déjà sur disque pour [version], s'il en est un vrai. */
    private fun fichierValide(context: Context, version: String): File? {
        val fichier = File(File(context.filesDir, DIR), nomFichier(version))
        if (!fichier.exists() || fichier.length() == 0L) return null
        val entete = ByteArray(2)
        val lus = runCatching { fichier.inputStream().use { it.read(entete) } }.getOrDefault(-1)
        return if (UpdateRegles.estUnApk(entete, lus)) fichier else null
    }

    /** Supprime les APK déjà installés ou périmés (55 Mo inutiles sinon). */
    private suspend fun purgerAnciens(context: Context) = withContext(Dispatchers.IO) {
        File(context.filesDir, DIR).listFiles()?.forEach {
            if (UpdateRegles.apkPerime(it.name, BuildConfig.VERSION_NAME)) it.delete()
        }
    }

    /** Bouton « Télécharger » du dialogue. */
    fun download(context: Context) {
        val available = _state.value as? UpdateState.Available ?: return
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = UpdateState.Downloading(available.version, 0f)
            val result = runCatching { fetch(app, available.version, available.apkUrl, silencieux = false) }
            _state.value = result.fold(
                onSuccess = { UpdateState.ReadyToInstall(available.version, it) },
                onFailure = {
                    Log.w(TAG, "téléchargement raté", it)
                    UpdateState.Failed(it.message, pendantTelechargement = true)
                },
            )
        }
    }

    private suspend fun fetch(context: Context, version: String, url: String, silencieux: Boolean): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, nomFichier(version))

            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", userAgent())
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) error(UpdateChecker.httpReason(code, null))
                val total = conn.contentLengthLong
                var ecrits = 0L
                var dernierPourcent = -1

                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lus = input.read(buffer)
                        while (lus != -1) {
                            output.write(buffer, 0, lus)
                            ecrits += lus
                            if (total > 0) {
                                // Une mise à jour d'état par pour cent, pas une par tampon.
                                val pourcent = (ecrits * 100 / total).toInt()
                                if (pourcent != dernierPourcent) {
                                    dernierPourcent = pourcent
                                    _state.value = UpdateState.Downloading(
                                        version, (ecrits.toFloat() / total).coerceIn(0f, 1f), silencieux,
                                    )
                                }
                            }
                            lus = input.read(buffer)
                        }
                    }
                }
                if (!UpdateRegles.telechargementComplet(ecrits, total)) {
                    target.delete()
                    error("téléchargement incomplet ($ecrits octets sur $total)")
                }
            } finally {
                conn.disconnect()
            }
            // Un fichier qui ne commence pas par « PK » est une page web, pas un APK.
            val entete = ByteArray(2)
            val lus = target.inputStream().use { it.read(entete) }
            if (!UpdateRegles.estUnApk(entete, lus)) {
                target.delete()
                error("fichier téléchargé invalide (ce n'est pas un APK)")
            }
            target
        }

    /** Bouton « Installer » : à n'appeler que si [canInstall]. */
    fun install(context: Context) {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        AutoUpdater.install(context, ready.file)
    }

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    /** Bouton « Autoriser » : réglage système des sources inconnues. */
    fun openInstallSettings(context: Context) = AutoUpdater.openInstallSettings(context)

    /** « Plus tard » : le dialogue se ferme pour cette version, le fichier téléchargé reste. */
    fun plusTard() {
        val version = when (val s = _state.value) {
            is UpdateState.Available -> s.version
            is UpdateState.Downloading -> s.version
            is UpdateState.ReadyToInstall -> s.version
            else -> null
        }
        if (version != null) _reportee.value = version
        else if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
    }
}
