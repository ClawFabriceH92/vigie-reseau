package com.trucdecomptable.vigieresau.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Cherche une version plus récente sur les releases GitHub.
 *
 * La release roulante `latest` porte `version.json` à côté de l'APK. Le manifeste
 * est lu en premier (l'API sans jeton est plafonnée à 60 appels par heure et par
 * adresse), mais il répond parfois 504 : l'API sert alors de repli, sans jamais
 * dépendre du manifeste. Un échec des deux sources n'est JAMAIS un « à jour ».
 *
 * Le dépôt doit rester PUBLIC : les assets d'un dépôt privé répondent 404 sans jeton.
 */
object UpdateChecker {

    const val REPO = "ClawFabriceH92/vigie-reseau"

    const val MANIFEST_URL = "https://github.com/$REPO/releases/download/latest/version.json"
    const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=5"
    const val APK_URL = "https://github.com/$REPO/releases/download/latest/app-release.apk"

    private const val TIMEOUT_MS = 10_000

    data class UpdateInfo(
        val version: String,
        val apkUrl: String,
        val notes: String = "",
    )

    /** Trois issues : une mise à jour, « à jour », ou « impossible de demander ». */
    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data class Unreachable(val reason: String, val answered: Boolean = false) : CheckResult
    }

    class HttpStatusException(val code: Int, reason: String) : IOException(reason)

    /** Contrôle réseau : manifeste, puis API en repli. [installed] = versionName installée. */
    suspend fun check(installed: String, userAgent: String): CheckResult =
        withContext(Dispatchers.IO) {
            val manifest = runCatching { fetch(MANIFEST_URL, github = false, userAgent = userAgent) }
            manifest.getOrNull()
                ?.let { parseManifest(it, installed) }
                ?.let { return@withContext it }

            val api = runCatching { fetch(API_URL, github = true, userAgent = userAgent) }
            api.getOrNull()
                ?.let { parseApi(it, installed) }
                ?.let { return@withContext it }

            failureVerdict(manifest, api)
        }

    /**
     * Verdict quand ni le manifeste ni l'API n'ont donné de réponse exploitable.
     * Une erreur réseau prime sur un code HTTP : elle dit que l'on n'a pas pu demander.
     */
    internal fun failureVerdict(manifest: Result<String>, api: Result<String>): CheckResult.Unreachable {
        val failures = listOfNotNull(manifest.exceptionOrNull(), api.exceptionOrNull())
        val reseau = failures.firstOrNull { it !is HttpStatusException }
        // L'erreur de l'API prime sur celle du manifeste : c'est la dernière source tentée.
        val cause = reseau ?: api.exceptionOrNull() ?: manifest.exceptionOrNull()
        val answered = cause == null || cause is HttpStatusException
        return CheckResult.Unreachable(cause?.let { describe(it) } ?: "réponse illisible", answered)
    }

    private fun fetch(url: String, github: Boolean, userAgent: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent)
            if (github) conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw HttpStatusException(code, httpReason(code, conn.getHeaderField("x-ratelimit-remaining")))
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    internal fun httpReason(code: Int, rateLimitRemaining: String?): String = when {
        code == HttpURLConnection.HTTP_FORBIDDEN && rateLimitRemaining == "0" ->
            "quota GitHub épuisé pour cette adresse IP (fréquent derrière un VPN à sortie partagée)"
        code == HttpURLConnection.HTTP_FORBIDDEN -> "accès refusé par GitHub (HTTP 403)"
        code == HttpURLConnection.HTTP_NOT_FOUND -> "ressource absente (HTTP 404)"
        code == HttpURLConnection.HTTP_GATEWAY_TIMEOUT -> "GitHub ne répond pas (HTTP 504)"
        else -> "HTTP $code"
    }

    internal fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "nom d'hôte introuvable, DNS injoignable (${e.message.orEmpty()})"
        is SocketTimeoutException -> "délai d'attente dépassé"
        is NoRouteToHostException -> "aucune route vers l'hôte"
        is ConnectException -> "connexion impossible"
        is SSLException -> "échec TLS (${e.message.orEmpty()})"
        is IOException -> e.message.orEmpty().ifBlank { "erreur réseau" }
        else -> "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
    }

    /**
     * Lecture du manifeste. Renvoie null si le corps n'est pas un manifeste lisible,
     * pour laisser l'API trancher : jamais un « à jour » par défaut.
     */
    internal fun parseManifest(body: String, installed: String): CheckResult? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val version = extractVersion(json.optString("version", "")) ?: return null
        val apkUrl = json.optString("apk", "").takeIf { urlApkAcceptable(it) } ?: APK_URL
        return if (compareVersions(version, installed) > 0) {
            CheckResult.Available(UpdateInfo(version, apkUrl, json.optString("notes", "")))
        } else {
            CheckResult.UpToDate
        }
    }

    /**
     * Lecture de la liste des releases. « À jour » n'est renvoyé que si au moins une
     * release exploitable (version lisible et APK joint) a été comparée. Sinon null :
     * une liste vide, une réponse d'erreur JSON ou une release sans APK ne prouvent rien.
     */
    internal fun parseApi(body: String, installed: String): CheckResult? {
        val releases = runCatching { JSONArray(body) }.getOrNull() ?: return null
        var comparees = 0
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft")) continue
            // La release roulante est taguée « latest » : la version est dans le titre.
            val version = extractVersion(release.optString("tag_name", ""))
                ?: extractVersion(release.optString("name", ""))
                ?: continue
            val assets = release.optJSONArray("assets") ?: JSONArray()
            val apkUrl = pickApk(assets, version) ?: continue
            comparees++
            if (compareVersions(version, installed) > 0) {
                return CheckResult.Available(UpdateInfo(version, apkUrl, release.optString("body", "")))
            }
        }
        return if (comparees > 0) CheckResult.UpToDate else null
    }

    /** Seules les URL https sont suivies : jamais de téléchargement d'APK en clair. */
    internal fun urlApkAcceptable(url: String): Boolean =
        url.startsWith("https://") && url.length > "https://".length

    fun extractVersion(text: String): String? =
        Regex("""\d+(?:\.\d+)+""").find(text)?.value

    fun pickApk(assets: JSONArray, version: String): String? {
        val apks = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name", "").endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val byVersion = apks.firstOrNull { it.optString("name").contains(version) }
        val canonical = apks.firstOrNull { it.optString("name") == "app-release.apk" }
        return (byVersion ?: canonical ?: apks.first()).optString("browser_download_url")
            .takeIf { urlApkAcceptable(it) }
    }

    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va > vb) return 1
            if (va < vb) return -1
        }
        return 0
    }
}
