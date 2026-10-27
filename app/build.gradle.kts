import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Signature : les secrets viennent de l'environnement en CI, et des fichiers
 * locaux sur ARGOS. Un APK non signé ne peut pas être installé par-dessus la
 * version précédente, donc la release doit toujours être signée.
 */
val keystoreEnv = listOf(
    File("/root/.secrets/keystores-android/vigieresau-keystore.env"),
).firstOrNull { it.exists() }?.readLines()?.mapNotNull { ligne ->
    val (cle, valeur) = ligne.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
    cle.trim() to valeur.trim()
}?.toMap().orEmpty()

val storePassword: String? = System.getenv("VIGIERESEAU_KEYSTORE_PASSWORD") ?: keystoreEnv["STORE_PASSWORD"]
val keyAliasValue: String = System.getenv("VIGIERESEAU_KEY_ALIAS") ?: keystoreEnv["KEY_ALIAS"] ?: "vigieresau"
val keyPasswordValue: String? = System.getenv("VIGIERESEAU_KEY_PASSWORD") ?: storePassword

val keystoreFile: File? = run {
    val local = File("/root/.secrets/keystores-android/vigieresau-release.keystore")
    val b64 = System.getenv("VIGIERESEAU_KEYSTORE_B64")
    when {
        b64 != null -> {
            val cible = File(System.getenv("RUNNER_TEMP") ?: System.getProperty("java.io.tmpdir"), "vigieresau-release.keystore")
            cible.writeBytes(Base64.getDecoder().decode(b64))
            cible
        }
        local.exists() -> local
        else -> null
    }
}

android {
    namespace = "com.trucdecomptable.vigieresau"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trucdecomptable.vigieresau"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("fr")
    }

    signingConfigs {
        if (keystoreFile != null && storePassword != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = this@Build_gradle.storePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
