package com.trucdecomptable.vigieresau

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.trucdecomptable.vigieresau.ui.Racine
import com.trucdecomptable.vigieresau.update.UpdateManager
import com.trucdecomptable.vigieresau.update.UpdateSchedule
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Contrôle de mise à jour au lancement.
        UpdateManager.checkAuto(applicationContext)

        // Puis toutes les 30 minutes tant que l'app est visible. Le gestionnaire
        // applique son propre plancher d'une heure : la boucle ne harcèle pas GitHub,
        // elle rattrape seulement un contrôle raté ou un retour au premier plan tardif.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(UpdateSchedule.BOUCLE_MS)
                    UpdateManager.checkAuto(applicationContext)
                }
            }
        }

        setContent {
            MaterialTheme {
                Racine()
            }
        }
    }
}
