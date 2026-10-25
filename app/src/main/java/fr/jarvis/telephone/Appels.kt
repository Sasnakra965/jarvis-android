package fr.jarvis.telephone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Appels passés à la demande de Jarvis (outil appeler), en deux temps :
 * - « chercher » : contact cherché comme pour les SMS (nom, ou nom d'un numéro), SANS appeler ; nom + numéro
 *   repartent à Jarvis, qui les fait confirmer ;
 * - « appeler » : appel du numéro confirmé par le système d'appel d'Android (TelecomManager.placeCall, marche
 *   téléphone verrouillé). Android envoie le son vers l'appareil Bluetooth mains libres connecté (voiture,
 *   oreillettes) s'il y en a un ; l'accusé dit lequel.
 */
object Appels {
    fun traiter(ctx: Context, a: Message.Appel) = if (a.action == "appeler") appeler(ctx, a) else chercher(ctx, a)

    private fun echec(ctx: Context, a: Message.Appel, pourquoi: String) {
        Prefs.ajouterHistorique(ctx, "📞 Appel de ${a.nom ?: a.destinataire} impossible : $pourquoi")
        Envois.accuser(a.ack, JSONObject().put("statut", "echec").put("detail", pourquoi))
    }

    private fun chercher(ctx: Context, a: Message.Appel) {
        val contacts = if (Envois.estNumero(a.destinataire)) listOf((nomDuNumero(ctx, a.destinataire) ?: a.destinataire) to a.destinataire) else {
            if (!Envois.autorise(ctx, Manifest.permission.READ_CONTACTS)) return echec(ctx, a, "l'appli Jarvis n'a pas accès aux contacts (Autorisations)")
            Envois.chercher(ctx, a.destinataire)
        }
        when {
            contacts.isEmpty() -> echec(ctx, a, "aucun contact « ${a.destinataire} » dans le téléphone")
            contacts.size > 1 -> {
                val choix = JSONArray()
                contacts.take(10).forEach { (nom, num) -> choix.put(JSONObject().put("nom", nom).put("numero", num)) }
                Envois.accuser(a.ack, JSONObject().put("statut", "choix").put("choix", choix))
            }
            else -> {
                val (nom, numero) = contacts.first()
                Envois.accuser(a.ack, JSONObject().put("statut", "trouve").put("nom", nom).put("numero", numero))
            }
        }
    }

    @SuppressLint("MissingPermission") // CALL_PHONE vérifiée juste avant
    private fun appeler(ctx: Context, a: Message.Appel) {
        val numero = a.numero?.takeIf { Envois.estNumero(it) } ?: return echec(ctx, a, "numéro manquant ou invalide")
        if (!Envois.autorise(ctx, Manifest.permission.CALL_PHONE)) return echec(ctx, a, "l'appli Jarvis n'a pas le droit de passer des appels (Autorisations)")
        val bluetooth = bluetoothConnecte(ctx)
        runCatching { ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", numero, null), Bundle()) }
            .onFailure { return echec(ctx, a, "l'appel n'a pas pu partir (${it.message})") }
        val nom = a.nom ?: numero
        Prefs.ajouterHistorique(ctx, "📞 Appel de $nom ($numero)${if (bluetooth) " en Bluetooth" else ""}")
        Envois.accuser(a.ack, JSONObject().put("statut", "appel_lance").put("audio", if (bluetooth) "bluetooth" else "telephone"))
    }

    /** Un appareil Bluetooth mains libres est connecté (voiture, oreillettes) : Android y envoie l'appel. */
    private fun bluetoothConnecte(ctx: Context): Boolean {
        val types = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        return ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    /** Nom du contact pour un numéro (null sans accès aux contacts ou numéro inconnu). */
    private fun nomDuNumero(ctx: Context, numero: String): String? {
        if (!Envois.autorise(ctx, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(numero))
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
