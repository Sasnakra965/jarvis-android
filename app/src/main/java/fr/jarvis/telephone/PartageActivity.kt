package fr.jarvis.telephone

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.UUID

/**
 * « Partager › Jarvis (télé) » depuis Google Photos ou la galerie : choix de l'écran (et de la vitesse du
 * diaporama), puis envoi à Jarvis, qui affiche sur la télé. Une vidéo à la fois ; plusieurs photos = diaporama.
 */
class PartageActivity : Activity() {
    private data class Element(val uri: Uri, val video: Boolean)

    private val dp get() = resources.displayMetrics.density
    private lateinit var col: LinearLayout
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val m = (20 * dp).toInt()
            setPadding(m, m, m, m)
            minimumWidth = (300 * dp).toInt()
        }
        setContentView(col)
        col.addView(TextView(this).apply {
            text = "Afficher sur la télé"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        message = TextView(this).apply { textSize = 16f; setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
        col.addView(message)

        if (Prefs.jetonJarvis(this).isEmpty()) {
            message.text = "L'appli n'est pas encore liée à Jarvis. Ouvre-la et touche « Lier à Jarvis »."
            col.addView(bouton("Ouvrir l'appli Jarvis") {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            })
            return
        }

        val (elements, ignores) = Jarvis.choisir(fichiers()) { it.video }
        if (elements.isEmpty()) {
            message.text = "Rien à afficher : seules les photos et les vidéos sont acceptées."
            col.addView(bouton("Fermer") { finish() })
            return
        }
        val video = elements[0].video
        message.text = buildString {
            append(if (video) "1 vidéo" else if (elements.size == 1) "1 photo" else "${elements.size} photos (diaporama)")
            if (ignores > 0) append("\n$ignores ${if (video) "autre(s) vidéo(s)" else "vidéo(s)"} laissée(s) de côté : une vidéo à la fois.")
            append("\n\nRecherche des écrans…")
        }
        Thread {
            val r = runCatching { Jarvis.ecrans(this) }
            runOnUiThread { if (!isFinishing) choix(elements, r) }
        }.start()
    }

    /** Fichiers partagés, avec leur sorte (photo ou vidéo). */
    private fun fichiers(): List<Element> {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
            else -> emptyList()
        }
        return uris.mapNotNull { uri ->
            val type = contentResolver.getType(uri) ?: intent.type ?: ""
            when {
                type.startsWith("video/") -> Element(uri, true)
                type.startsWith("image/") -> Element(uri, false)
                else -> null
            }
        }
    }

    private fun choix(elements: List<Element>, ecrans: Result<List<String>>) {
        val liste = ecrans.getOrElse {
            message.text = it.message
            col.addView(bouton("Fermer") { finish() })
            return
        }
        if (liste.isEmpty()) {
            message.text = "Aucun écran n'est réglé dans Jarvis."
            col.addView(bouton("Fermer") { finish() })
            return
        }
        message.text = message.text.removeSuffix("\n\nRecherche des écrans…")

        val groupeEcrans = RadioGroup(this)
        liste.forEachIndexed { i, nom ->
            groupeEcrans.addView(RadioButton(this).apply { id = 1000 + i; text = nom; textSize = 17f })
        }
        groupeEcrans.check(1000 + liste.indexOf(Prefs.ecran(this)).coerceAtLeast(0))
        if (liste.size > 1) col.addView(sousTitre("Écran"))
        col.addView(groupeEcrans.apply { visibility = if (liste.size > 1) RadioGroup.VISIBLE else RadioGroup.GONE })

        val vitesses = listOf(3, 5, 10, 30)
        val groupeVitesse = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        vitesses.forEach { s -> groupeVitesse.addView(RadioButton(this).apply { id = 2000 + s; text = "$s s" }) }
        groupeVitesse.check(2000 + (Prefs.secondes(this).takeIf { it in vitesses } ?: 5))
        if (elements.size > 1) {
            col.addView(sousTitre("Photo suivante toutes les"))
            col.addView(groupeVitesse)
        }

        lateinit var afficher: Button
        afficher = bouton("Afficher") {
            val ecran = liste[groupeEcrans.checkedRadioButtonId - 1000]
            val secondes = groupeVitesse.checkedRadioButtonId - 2000
            Prefs.garderChoix(this, ecran, secondes)
            afficher.isEnabled = false
            envoyer(elements, ecran, secondes) { afficher.isEnabled = true }
        }
        col.addView(afficher)
        col.addView(bouton("Annuler") { finish() })
    }

    private fun envoyer(elements: List<Element>, ecran: String, secondes: Int, rate: () -> Unit) {
        val lot = UUID.randomUUID().toString().take(8)
        Thread {
            try {
                elements.forEachIndexed { i, e ->
                    runOnUiThread { message.text = if (elements.size > 1) "Envoi ${i + 1}/${elements.size}…" else "Envoi…" }
                    val params = mapOf(
                        "ecran" to ecran,
                        "sorte" to if (e.video) "video" else "photo",
                        "lot" to lot,
                        "index" to "$i",
                        "total" to "${elements.size}",
                        "secondes" to "$secondes",
                    )
                    envoyerUn(e.uri, params)
                }
                runOnUiThread {
                    Toast.makeText(this, "C'est sur la télé 📺", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    message.text = "Pas réussi : ${ex.message}"
                    rate()
                }
            }
        }.start()
    }

    /** Taille connue : envoi direct ; sinon copie temporaire dans l'appli (effacée aussitôt). */
    private fun envoyerUn(uri: Uri, params: Map<String, String>) {
        val taille = runCatching { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (taille > 0) {
            Jarvis.envoyer(this, params, taille) { contentResolver.openInputStream(uri) ?: throw Jarvis.Erreur("fichier illisible") }
            return
        }
        val copie = File.createTempFile("partage", null, cacheDir)
        try {
            (contentResolver.openInputStream(uri) ?: throw Jarvis.Erreur("fichier illisible")).use { e ->
                copie.outputStream().use { s -> e.copyTo(s) }
            }
            Jarvis.envoyer(this, params, copie.length()) { copie.inputStream() }
        } finally {
            copie.delete()
        }
    }

    private fun sousTitre(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
    }

    private fun bouton(t: String, quoi: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { quoi() }
    }
}
