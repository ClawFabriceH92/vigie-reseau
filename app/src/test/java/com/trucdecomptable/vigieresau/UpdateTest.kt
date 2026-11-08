package com.trucdecomptable.vigieresau

import com.trucdecomptable.vigieresau.update.UpdateChecker
import com.trucdecomptable.vigieresau.update.UpdateChecker.CheckResult
import com.trucdecomptable.vigieresau.update.UpdateRegles
import com.trucdecomptable.vigieresau.update.UpdateSchedule
import com.trucdecomptable.vigieresau.update.UpdateState
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UpdateTest {

    // ---- Versions ----

    @Test
    fun `comparaison de versions segment par segment`() {
        assertTrue(UpdateChecker.compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.0", "1.0.1") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.0.1", "1.0.1"))
        assertTrue(UpdateChecker.compareVersions("2.0", "1.9.9") > 0)
        assertEquals(0, UpdateChecker.compareVersions("1.1.0", "1.1"))
    }

    @Test
    fun `version extraite d un titre de release`() {
        assertEquals("1.3.0", UpdateChecker.extractVersion("Vigie Réseau v1.3.0"))
        assertNull(UpdateChecker.extractVersion("latest"))
    }

    // ---- Manifeste ----

    @Test
    fun `manifeste plus recent donne une mise a jour`() {
        val body = """{"version":"1.0.2","notes":"Build automatique - v1.0.2","apk":"https://exemple/app-release.apk"}"""
        val r = UpdateChecker.parseManifest(body, "1.0.1") as CheckResult.Available
        assertEquals("1.0.2", r.info.version)
        assertEquals("https://exemple/app-release.apk", r.info.apkUrl)
    }

    @Test
    fun `manifeste identique ou plus ancien est a jour`() {
        assertTrue(UpdateChecker.parseManifest("""{"version":"1.0.1"}""", "1.0.1") is CheckResult.UpToDate)
        assertTrue(UpdateChecker.parseManifest("""{"version":"1.0.0"}""", "1.0.1") is CheckResult.UpToDate)
    }

    @Test
    fun `manifeste sans apk retombe sur l APK canonique`() {
        val r = UpdateChecker.parseManifest("""{"version":"1.0.2"}""", "1.0.1") as CheckResult.Available
        assertEquals(UpdateChecker.APK_URL, r.info.apkUrl)
    }

    @Test
    fun `manifeste avec URL http en clair est refusee`() {
        val r = UpdateChecker.parseManifest("""{"version":"1.0.2","apk":"http://exemple/a.apk"}""", "1.0.1") as CheckResult.Available
        assertEquals(UpdateChecker.APK_URL, r.info.apkUrl)
    }

    @Test
    fun `manifeste illisible renvoie null pour laisser parler l API`() {
        assertNull(UpdateChecker.parseManifest("<html>504 Gateway Time-out</html>", "1.0.1"))
        assertNull(UpdateChecker.parseManifest("""{"notes":"sans version"}""", "1.0.1"))
        assertNull(UpdateChecker.parseManifest("", "1.0.1"))
    }

    // ---- API ----

    @Test
    fun `API release roulante latest lue via le titre`() {
        val body = """
            [{"tag_name":"latest","name":"Vigie Réseau v1.0.2","draft":false,
              "assets":[{"name":"app-release.apk","browser_download_url":"https://exemple/app-release.apk"},
                        {"name":"version.json","browser_download_url":"https://exemple/version.json"}]}]
        """.trimIndent()
        val r = UpdateChecker.parseApi(body, "1.0.1") as CheckResult.Available
        assertEquals("1.0.2", r.info.version)
        assertEquals("https://exemple/app-release.apk", r.info.apkUrl)
    }

    @Test
    fun `API release identique est a jour`() {
        val body = """
            [{"tag_name":"latest","name":"Vigie Réseau v1.0.1","draft":false,
              "assets":[{"name":"app-release.apk","browser_download_url":"https://exemple/app-release.apk"}]}]
        """.trimIndent()
        assertTrue(UpdateChecker.parseApi(body, "1.0.1") is CheckResult.UpToDate)
    }

    @Test
    fun `API brouillon ignore et sans preuve donc pas de faux a jour`() {
        val body = """[{"tag_name":"v9.9.9","name":"v9.9.9","draft":true,
            "assets":[{"name":"app-release.apk","browser_download_url":"https://exemple/a.apk"}]}]"""
        assertNull(UpdateChecker.parseApi(body, "1.0.1"))
    }

    @Test
    fun `API sans APK ou liste vide ou erreur JSON ne prouve rien`() {
        assertNull(UpdateChecker.parseApi("""[{"tag_name":"v2.0.0","name":"v2.0.0","draft":false,"assets":[]}]""", "1.0.1"))
        assertNull(UpdateChecker.parseApi("[]", "1.0.1"))
        assertNull(UpdateChecker.parseApi("""{"message":"API rate limit exceeded"}""", "1.0.1"))
        assertNull(UpdateChecker.parseApi("<html>502</html>", "1.0.1"))
    }

    @Test
    fun `API une release plus recente est trouvee derriere une plus ancienne`() {
        val body = """
            [{"tag_name":"v1.0.0","name":"v1.0.0","draft":false,
              "assets":[{"name":"app-release.apk","browser_download_url":"https://exemple/a100.apk"}]},
             {"tag_name":"v1.2.0","name":"v1.2.0","draft":false,
              "assets":[{"name":"app-release.apk","browser_download_url":"https://exemple/a120.apk"}]}]
        """.trimIndent()
        val r = UpdateChecker.parseApi(body, "1.0.1") as CheckResult.Available
        assertEquals("1.2.0", r.info.version)
    }

    @Test
    fun `choix de l APK par version puis nom canonique`() {
        val assets = JSONArray(
            """[{"name":"notes.txt","browser_download_url":"https://exemple/n.txt"},
                {"name":"autre.apk","browser_download_url":"https://exemple/autre.apk"},
                {"name":"app-release.apk","browser_download_url":"https://exemple/app-release.apk"}]""",
        )
        assertEquals("https://exemple/app-release.apk", UpdateChecker.pickApk(assets, "1.0.2"))
        assertNull(UpdateChecker.pickApk(JSONArray("""[{"name":"notes.txt"}]"""), "1.0.2"))
    }

    // ---- Verdict d'échec : jamais « à jour » ----

    @Test
    fun `manifeste 504 et API muette donnent l echec HTTP du manifeste`() {
        val v = UpdateChecker.failureVerdict(
            Result.failure(UpdateChecker.HttpStatusException(504, "GitHub ne répond pas (HTTP 504)")),
            Result.success("""{"message":"quota"}"""),
        )
        assertEquals("GitHub ne répond pas (HTTP 504)", v.reason)
        assertTrue(v.answered)
    }

    @Test
    fun `deux reponses illisibles donnent un echec et non un a jour`() {
        val v = UpdateChecker.failureVerdict(Result.success("<html>"), Result.success("""{"message":"quota"}"""))
        assertEquals("réponse illisible", v.reason)
        assertTrue(v.answered)
    }

    @Test
    fun `reseau coupe prime sur un code HTTP`() {
        val v = UpdateChecker.failureVerdict(
            Result.failure(UpdateChecker.HttpStatusException(504, "HTTP 504")),
            Result.failure(UnknownHostException("api.github.com")),
        )
        assertFalse(v.answered)
        assertTrue(v.reason.contains("DNS injoignable"))
    }

    @Test
    fun `delai depasse et erreurs HTTP sont decrits`() {
        assertEquals("délai d'attente dépassé", UpdateChecker.describe(SocketTimeoutException()))
        assertEquals("erreur réseau", UpdateChecker.describe(IOException()))
        assertEquals("GitHub ne répond pas (HTTP 504)", UpdateChecker.httpReason(504, null))
        assertTrue(UpdateChecker.httpReason(403, "0").contains("quota"))
    }

    // ---- Cadence ----

    @Test
    fun `calendrier des verifications`() {
        assertTrue(UpdateSchedule.isDue(manual = true, lastCheckAt = 1_000L, now = 1_001L))
        assertTrue(UpdateSchedule.isDue(manual = false, lastCheckAt = UpdateSchedule.NEVER, now = 123L))
        assertFalse(UpdateSchedule.isDue(manual = false, lastCheckAt = 1_000L, now = 2_000L))
        assertFalse(UpdateSchedule.isDue(manual = false, lastCheckAt = 1_000L, now = 1_000L + UpdateSchedule.MIN_INTERVAL_MS - 1))
        assertTrue(UpdateSchedule.isDue(manual = false, lastCheckAt = 1_000L, now = 1_000L + UpdateSchedule.MIN_INTERVAL_MS))
    }

    @Test
    fun `la boucle de 30 minutes passe sous le plancher d une heure`() {
        assertEquals(30 * 60_000L, UpdateSchedule.BOUCLE_MS)
        // Premier tick de la boucle : encore sous le plancher, aucun appel réseau.
        assertFalse(UpdateSchedule.isDue(false, 1_000L, 1_000L + UpdateSchedule.BOUCLE_MS))
        // Deuxième tick : le plancher est franchi.
        assertTrue(UpdateSchedule.isDue(false, 1_000L, 1_000L + 2 * UpdateSchedule.BOUCLE_MS))
    }

    // ---- Fichier téléchargé ----

    @Test
    fun `magic bytes PK exiges`() {
        assertTrue(UpdateRegles.estUnApk(byteArrayOf(0x50, 0x4B)))
        assertFalse(UpdateRegles.estUnApk("<!".toByteArray()))
        assertFalse(UpdateRegles.estUnApk(byteArrayOf(0x50, 0x4B), lus = 1))
        assertFalse(UpdateRegles.estUnApk(byteArrayOf(0x50, 0x4B), lus = -1))
    }

    @Test
    fun `telechargement tronque refuse quand la taille est connue`() {
        assertTrue(UpdateRegles.telechargementComplet(1000, 1000))
        assertFalse(UpdateRegles.telechargementComplet(999, 1000))
        assertTrue(UpdateRegles.telechargementComplet(500, -1))
        assertFalse(UpdateRegles.telechargementComplet(0, -1))
    }

    @Test
    fun `purge des APK deja installes ou illisibles`() {
        assertTrue(UpdateRegles.apkPerime("vigie-reseau-v1.0.1.apk", "1.0.1"))
        assertTrue(UpdateRegles.apkPerime("vigie-reseau-v1.0.0.apk", "1.0.1"))
        assertFalse(UpdateRegles.apkPerime("vigie-reseau-v1.0.2.apk", "1.0.1"))
        assertTrue(UpdateRegles.apkPerime("n-importe-quoi.tmp", "1.0.1"))
    }

    // ---- Surface visible ----

    private val dispo = UpdateState.Available("1.0.2", "", "https://exemple/a.apk")

    @Test
    fun `dialogue visible pour une mise a jour sauf version repoussee`() {
        assertTrue(UpdateRegles.dialogueVisible(dispo, null))
        assertFalse(UpdateRegles.dialogueVisible(dispo, "1.0.2"))
        assertTrue(UpdateRegles.dialogueVisible(dispo, "1.0.1"))
        assertTrue(UpdateRegles.dialogueVisible(UpdateState.ReadyToInstall("1.0.2", File("x.apk")), null))
        assertFalse(UpdateRegles.dialogueVisible(UpdateState.ReadyToInstall("1.0.2", File("x.apk")), "1.0.2"))
    }

    @Test
    fun `pre-telechargement silencieux sans dialogue, echec de controle sans dialogue`() {
        assertFalse(UpdateRegles.dialogueVisible(UpdateState.Downloading("1.0.2", 0.3f, silencieux = true), null))
        assertTrue(UpdateRegles.dialogueVisible(UpdateState.Downloading("1.0.2", 0.3f, silencieux = false), null))
        assertFalse(UpdateRegles.dialogueVisible(UpdateState.Failed("HTTP 504"), null))
        assertTrue(UpdateRegles.dialogueVisible(UpdateState.Failed("HTTP 504", pendantTelechargement = true), null))
        assertFalse(UpdateRegles.dialogueVisible(UpdateState.UpToDate("1.0.1"), null))
        assertFalse(UpdateRegles.dialogueVisible(UpdateState.Idle, null))
    }

    @Test
    fun `statut des reglages distingue a jour et erreur reseau`() {
        val aJour = UpdateRegles.libelleStatut(UpdateState.UpToDate("1.0.1"), "1.0.1")
        val erreur = UpdateRegles.libelleStatut(UpdateState.Failed("GitHub ne répond pas (HTTP 504)"), "1.0.1")
        assertTrue(aJour.startsWith("À jour"))
        assertTrue(erreur.startsWith("Vérification impossible"))
        assertTrue(erreur.contains("HTTP 504"))
        assertFalse(erreur.startsWith("À jour"))
        assertEquals("Vérification en cours…", UpdateRegles.libelleStatut(UpdateState.Checking, "1.0.1"))
        assertEquals("Mise à jour disponible : v1.0.2", UpdateRegles.libelleStatut(dispo, "1.0.1"))
    }

    @Test
    fun `un controle automatique ne redemande rien quand une mise a jour est deja en main`() {
        assertTrue(UpdateRegles.miseAJourEnMain(dispo))
        assertTrue(UpdateRegles.miseAJourEnMain(UpdateState.Downloading("1.0.2", 0f)))
        assertTrue(UpdateRegles.miseAJourEnMain(UpdateState.ReadyToInstall("1.0.2", File("x.apk"))))
        assertFalse(UpdateRegles.miseAJourEnMain(UpdateState.Idle))
        assertFalse(UpdateRegles.miseAJourEnMain(UpdateState.Failed("x")))
    }
}
