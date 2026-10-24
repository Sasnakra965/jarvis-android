package fr.jarvis.telephone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Écran unique : réglages, autorisations, tests, réveils programmés et historique. */
class MainActivity : Activity() {
    private lateinit var etat: TextView
    private lateinit var autorisations: LinearLayout
    private lateinit var reveils: TextView
    private lateinit var historique: TextView
    private lateinit var lienJarvis: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val rafraichir = object : Runnable {
        override fun run() {
            majEtat()
            ui.postDelayed(this, 2_000)
        }
    }

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifs.creerCanaux(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val m = (16 * dp).toInt()
            setPadding(m, m, m, m)
        }
        setContentView(ScrollView(this).apply { addView(col) })

        etat = TextView(this).apply { textSize = 18f; setTypeface(typeface, Typeface.BOLD) }
        col.addView(etat)

        col.addView(titre("Connexion"))
        val serveur = champ(Prefs.serveur(this), "Adresse du serveur", InputType.TYPE_TEXT_VARIATION_URI)
        val sujet = champ(Prefs.sujet(this), "Sujet (canal secret)", InputType.TYPE_CLASS_TEXT)
        col.addView(serveur)
        col.addView(sujet)
        col.addView(bouton("Enregistrer et connecter") {
            Prefs.enregistrer(this, serveur.text.toString(), sujet.text.toString())
            ListenerService.redemarrer(this)
            Toast.makeText(this, "Enregistré", Toast.LENGTH_SHORT).show()
        })

        col.addView(titre("Télé (partage de photos)"))
        lienJarvis = TextView(this).apply { textSize = 15f }
        col.addView(lienJarvis)
        col.addView(bouton("Lier à Jarvis (scanner le QR code)") { scannerQr() })

        col.addView(titre("Lecture vocale"))
        col.addView(Switch(this).apply {
            text = "Lire à voix haute les alertes importantes (départ, trafic, rappels importants), volume au maximum, écouteurs Bluetooth sinon haut-parleur"
            textSize = 15f
            isChecked = Prefs.lectureVocale(this@MainActivity)
            setOnCheckedChangeListener { _, oui -> Prefs.lectureVocale(this@MainActivity, oui) }
        })
        col.addView(bouton("Tester la lecture vocale") {
            Lecteur.lire(this, "Test de Jarvis. C'est l'heure de partir pour ton rendez-vous à Aubagne, 25 minutes de route.", alerte = true)
        })

        col.addView(titre("Autorisations"))
        autorisations = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(autorisations)

        col.addView(titre("Tests"))
        col.addView(bouton("Envoyer une notification de test") {
            publier(JSONObject().put("type", "notif").put("titre", "Jarvis").put("message", "Test réussi 👌").put("priorite", "haute"))
        })
        col.addView(bouton("Tester un réveil dans 1 minute") {
            val quand = System.currentTimeMillis() / 1000 + 60
            publier(
                JSONObject().put("type", "reveil").put("id", "test").put("timestamp", quand)
                    .put("libelle", "Réveil de test")
            )
        })

        col.addView(titre("Réveils programmés"))
        reveils = TextView(this).apply { textSize = 15f }
        col.addView(reveils)
        col.addView(bouton("Annuler tous les réveils") {
            val n = Alarms.annuler(this, null)
            Toast.makeText(this, "$n réveil(s) annulé(s)", Toast.LENGTH_SHORT).show()
            majEtat()
        })

        col.addView(titre("Messages reçus"))
        historique = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        col.addView(historique)
        col.addView(bouton("Effacer l'historique") { Prefs.effacerHistorique(this); majEtat() })

        // Premier lancement : autoriser les notifications.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ListenerService.demarrer(this)
    }

    override fun onResume() {
        super.onResume()
        majAutorisations()
        // Appli liée avant le chiffrement des messages : on récupère la clé une fois.
        if (Prefs.jetonJarvis(this).isNotEmpty() && Prefs.cleNtfy(this).isEmpty()) {
            Thread { runCatching { Jarvis.recupererCle(this) } }.start()
        }
        ui.post(rafraichir)
    }

    override fun onPause() {
        ui.removeCallbacks(rafraichir)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        majAutorisations()
    }

    private fun titre(t: String) = TextView(this).apply {
        text = t
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (24 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun champ(valeur: String, aide: String, type: Int) = EditText(this).apply {
        setText(valeur)
        hint = aide
        inputType = InputType.TYPE_CLASS_TEXT or type
        isSingleLine = true
    }

    private fun bouton(t: String, quoi: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { quoi() }
    }

    private fun majEtat() {
        etat.text = if (Prefs.sujet(this).isEmpty()) "Jarvis : renseigne le sujet ci-dessous" else "Jarvis : ${ListenerService.etat}"
        lienJarvis.text = if (Prefs.jetonJarvis(this).isEmpty())
            "Pas encore liée. Sur le PC : Jarvis › Téléphone › QR code, puis touche le bouton ci-dessous.\n" +
                "Ensuite, dans Google Photos : Partager › Jarvis (télé)."
        else "✔ Liée à ${Uri.parse(Prefs.jarvis(this)).host}. Dans Google Photos : Partager › Jarvis (télé)."
        val r = Prefs.reveils(this)
        reveils.text = if (r.isEmpty()) "Aucun" else r.joinToString("\n") { "• ${Alarms.heure(it.quand)} · ${it.libelle}" }
        val f = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
        val h = Prefs.historique(this)
        historique.text = if (h.isEmpty()) "Aucun message pour l'instant" else
            h.joinToString("\n\n") { (t, texte) -> "${f.format(Date(t))}  $texte" }
    }

    /** Liste des autorisations : ✔ si accordée, sinon un bouton qui ouvre le bon réglage. */
    @SuppressLint("BatteryLife")
    private fun majAutorisations() {
        autorisations.removeAllViews()
        fun ligne(nom: String, ok: Boolean, ouvrir: () -> Unit) {
            autorisations.addView(
                if (ok) TextView(this).apply { text = "✔ $nom"; textSize = 16f; setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt()) }
                else bouton("Autoriser : $nom") { ouvrir() }
            )
        }
        val nm = Notifs.nm(this)
        ligne("notifications", nm.areNotificationsEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        ligne("fonctionner sans limite de batterie", pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        ligne("alarmes à l'heure exacte", Alarms.peutProgrammer(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        ligne("envoyer des SMS et lire les contacts (messages de Jarvis)",
            Envois.autorise(this, Manifest.permission.SEND_SMS) && Envois.autorise(this, Manifest.permission.READ_CONTACTS)) {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS), 2)
        }
        ligne("envoi WhatsApp automatique (Accessibilité › Jarvis : envoi WhatsApp)", Envois.serviceActif(this)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val pleinEcran = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm.canUseFullScreenIntent()
        ligne("réveil en plein écran", pleinEcran) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
    }

    /** QR code de Jarvis (Jarvis › Téléphone, sur le PC) : lu par les services Google, sans autorisation caméra. */
    private fun scannerQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code ->
                val lien = code.rawValue ?: ""
                Thread {
                    val r = runCatching { Jarvis.lier(this, lien) }
                    ui.post {
                        Toast.makeText(this, r.exceptionOrNull()?.message ?: "Liée à Jarvis 👌", Toast.LENGTH_LONG).show()
                        majEtat()
                    }
                }.start()
            }
            .addOnFailureListener { Toast.makeText(this, "Lecture du QR code impossible : ${it.message}", Toast.LENGTH_LONG).show() }
    }

    /** Envoie un message sur le sujet : teste tout le trajet (serveur → téléphone). */
    private fun publier(msg: JSONObject) {
        val serveur = Prefs.serveur(this)
        val sujet = Prefs.sujet(this)
        if (sujet.isEmpty()) {
            Toast.makeText(this, "Renseigne d'abord le sujet", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val ok = runCatching {
                val c = URL("$serveur/${URLEncoder.encode(sujet, "UTF-8")}").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                c.outputStream.use { it.write(msg.toString().toByteArray()) }
                val code = c.responseCode
                c.disconnect()
                code == 200
            }.getOrDefault(false)
            ui.post {
                Toast.makeText(this, if (ok) "Envoyé : ça doit arriver dans un instant" else "Envoi impossible (Internet ?)", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
