# Jarvis — appli Android

Petite appli Android qui reçoit les notifications et les réveils de [Jarvis](https://github.com/Azhabel/jarvis-assistant)
via [ntfy](https://ntfy.sh), sans MacroDroid ni Chrome :

- connexion permanente (service au premier plan), démarre avec le téléphone, se reconnecte seule et récupère les messages manqués ;
- notifications immédiates, même écran éteint ;
- vrais réveils (`AlarmManager.setAlarmClock`) : écran plein avec sonnerie, boutons **Arrêter** et **Snooze 10 min**, annulation à distance ;
- écran unique : serveur, sujet, autorisations, tests, réveils programmés, historique.

## Installer

Sur le téléphone, ouvrir **https://github.com/Sasnakra965/jarvis-android/releases/latest/download/jarvis.apk**,
installer (autoriser « sources inconnues » pour le navigateur la première fois), ouvrir Jarvis,
renseigner le **sujet** (le même que `NTFY_TOPIC` dans le `.env` de Jarvis) puis accorder les autorisations listées.

## Format des messages

Corps d'un message publié sur le sujet ntfy (`POST https://ntfy.sh/<sujet>`), en JSON :

| type | champs | effet |
|---|---|---|
| `notif` | `titre`, `message`, `priorite` : `basse` \| `normale` \| `haute` \| `urgente`, `url` (facultatif) | notification (haute/urgente : canal « Alertes importantes ») ; toucher ouvre `url` |
| `reveil` | `id`, `timestamp` (secondes) **ou** `heure` + `minute`, `libelle` | programme une alarme ; même `id` = remplace |
| `annuler_reveil` | `id` (facultatif) | annule ce réveil, ou tous sans `id` |

```json
{"type":"notif","titre":"Jarvis","message":"Sauvegarde ratée","priorite":"haute"}
{"type":"reveil","id":"r-1761198600","heure":7,"minute":30,"timestamp":1761198600,"libelle":"Chantier Dupont"}
{"type":"annuler_reveil","id":"r-1761198600"}
```

`timestamp` prime sur `heure`/`minute` ; sans lui, c'est la prochaine fois qu'il est `heure:minute` (heure du
téléphone) après l'envoi. Un réveil dont l'heure est passée est ignoré. Un message qui n'est pas du JSON
s'affiche tel quel comme notification (titre et priorité ntfy).

Le sujet fait office de mot de passe : quiconque le connaît peut envoyer des messages. Choisir un nom long et aléatoire.

## Développer

Kotlin, aucune dépendance (API Android seules). `gradle assembleRelease` (Gradle 8.11, JDK 17, SDK 35).
Chaque push sur `main` compile, teste, signe et publie une Release (`.github/workflows/apk.yml`) ;
secrets du dépôt : `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`.
