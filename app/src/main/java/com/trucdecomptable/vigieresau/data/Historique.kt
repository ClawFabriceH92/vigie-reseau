package com.trucdecomptable.vigieresau.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Historique des requêtes DNS, gardé sur le téléphone.
 *
 * Les requêtes ne vivaient qu'en mémoire : tout disparaissait au redémarrage de
 * l'app. Elles sont maintenant écrites en base (rétention de quelques jours) et
 * rechargées au lancement.
 */
class Historique(context: Context) : SQLiteOpenHelper(context, NOM, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "horodatage INTEGER NOT NULL, " +
                "ip TEXT NOT NULL, " +
                "domaine TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX idx_requete_horodatage ON $TABLE(horodatage)")
        db.execSQL("CREATE INDEX idx_requete_ip ON $TABLE(ip)")
    }

    override fun onUpgrade(db: SQLiteDatabase, ancienne: Int, nouvelle: Int) {
        // Aucune migration à ce jour : l'historique est reconstructible.
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /** Ajoute une requête ; faux si l'écriture a échoué (disque plein, base fermée). */
    fun ajouter(horodatage: Long, ip: String, domaine: String): Boolean = try {
        writableDatabase.insert(TABLE, null, ContentValues().apply {
            put("horodatage", horodatage)
            put("ip", ip)
            put("domaine", domaine)
        }) != -1L
    } catch (e: Exception) {
        false
    }

    /** Les [limite] requêtes les plus récentes, rendues de la plus ancienne à la plus récente. */
    fun charger(limite: Int = LIMITE): List<RequeteDns> = try {
        val liste = ArrayList<RequeteDns>()
        readableDatabase.rawQuery(
            "SELECT horodatage, ip, domaine FROM $TABLE ORDER BY horodatage DESC LIMIT $limite",
            null,
        ).use { curseur ->
            while (curseur.moveToNext()) {
                liste += RequeteDns(curseur.getLong(0), curseur.getString(1), curseur.getString(2))
            }
        }
        liste.asReversed().toList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Supprime les requêtes plus vieilles que [seuil] (millisecondes). Nombre de lignes ôtées. */
    fun purgerAvant(seuil: Long): Int = try {
        writableDatabase.delete(TABLE, "horodatage < ?", arrayOf(seuil.toString()))
    } catch (e: Exception) {
        0
    }

    fun vider() {
        try {
            writableDatabase.delete(TABLE, null, null)
        } catch (e: Exception) {
            // Base indisponible : rien à faire, l'écran est déjà vidé.
        }
    }

    fun compter(): Int = try {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { curseur ->
            if (curseur.moveToFirst()) curseur.getInt(0) else 0
        }
    } catch (e: Exception) {
        0
    }

    companion object {
        private const val NOM = "vigie-historique.db"
        private const val VERSION = 1
        private const val TABLE = "requete"

        /** Nombre de requêtes rechargées en mémoire au lancement. */
        const val LIMITE = 5_000

        /** Plafond de lignes lues pour un export CSV. */
        const val EXPORT_MAX = 200_000

        /** Rétention de l'historique sur le disque. */
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
