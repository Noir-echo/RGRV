# RGRV (ReGroove) — contrôleur, séquenceur, effets MIDI et synthés intégrés pour Android

RGRV reprend tout RKTRL (contrôleur 128 boutons, séquenceur 16 pistes, Destroy Mode) et ajoute
une **4e page SYNTH** : un rack de 6 instruments joués directement par le téléphone, plus un bouton
**REC** pour enregistrer ce que l'on joue en fichier audio.

Android 8.0 minimum, paysage. Connexion USB-MIDI ou Bluetooth MIDI (BLE) facultative :
sans instrument externe, l'application joue seule avec ses synthés.

---

## 1. Obtenir l'APK (GitHub, sans rien installer)

1. Créer un dépôt GitHub `RGRV` et y envoyer **le contenu** de ce dossier
   (`app/`, `gradle/`, `build.gradle.kts`, `settings.gradle.kts`, `.github/`…).
2. *Settings → Secrets and variables → Actions* : créer `RGRV_KEYSTORE_B64` et `RGRV_KEYSTORE_PASSWORD`
   (mêmes valeurs que pour RKTRL : la clé de signature ne doit **jamais** être dans le dépôt).
3. Onglet **Actions** : le workflow *Build APK* produit `RGRV.apk` et le publie dans *Releases*.

RGRV a son propre identifiant (`fr.rgrv.app`) : il s'installe **à côté** de RKTRL.

---

## 2. Les pages

| Onglet | Page |
|---|---|
| `ctrl / 01` | Contrôleur : 128 boutons (canal + CC), fader avec accroche, groupes, LATCH |
| `seq / 02` | Séquenceur 16 pistes × 1024 pas, AUTO, Euclid, RANDOM, A/B, expressions |
| `dstr / 03` | Destroy Mode : pad X/Y d'effets MIDI, pads de notes, TAP BPM |
| `synth / 04` | **Rack de synthés** (nouveau) |

---

## 3. Page SYNTH

Six rangées, une par rack. Chaque rangée :

- **Nom de l'instrument + canal** (toucher pour changer d'instrument ou de canal MIDI) et vumètre ;
- **4 macros** propres à l'instrument, choisies pour changer fortement le son ;
- **4 effets** : DRIVE (saturation), CRUSH (bits / échantillonnage), DELAY (envoi vers un délai
  ping-pong calé sur le tempo), REVERB (envoi vers la réverbération), puis LEVEL ;
- **TEST** (maintenir = joue une note) et **MUTE**.

Glisser sur un bouton pour le tourner (haut / droite = plus), **double-toucher** pour le remettre à sa valeur de départ.
En bas : un clavier d'une octave (OCT −/+) qui joue le rack sélectionné, ▶ PLAY, PANIC.

### Instruments (synthèses originales, libres de droits)

| Nom | Esprit | Macros |
|---|---|---|
| **KIT** | boîte à rythmes synthétique, 8 sons sur les notes 36–43 (puis en boucle) | TUNE · DECAY · TONE · CHAOS |
| **ACID** | basse acid monophonique (vélocité ≥ 110 = accent, notes liées = slide) | CUTOFF · RESO · ENV · DECAY |
| **MONO** | gros mono 3 oscillateurs, filtre 24 dB, glissé | CUTOFF · RESO · CONTOUR · GLIDE |
| **SUPER** | polyphonique supersaw (nappes, accords, leads larges) | CUTOFF · SPREAD · RESO · SHAPE |
| **FM** | polyphonique FM 2 opérateurs (cloches, piano électrique, basses métalliques) | RATIO · DEPTH · DECAY · BRIGHT |

Tout le code de synthèse est écrit pour RGRV (algorithmes classiques du domaine public : PolyBLEP,
filtre à variable d'état, filtre en échelle, réverbération de structure Freeverb). Aucun échantillon,
aucun nom de marque : les instruments s'inspirent de *styles* de synthèse, pas de produits.

### Canaux MIDI : comme une vraie machine

Chaque rack écoute un canal MIDI. Tout ce qui est envoyé sur ce canal — séquenceur, contrôleur,
Destroy Mode, pads — joue le rack **au lieu** de partir vers l'appareil externe.
Par défaut : KIT canal 12, ACID 13, MONO 14, SUPER 15, FM 16 (le SEQTRAK utilise 1 à 11, les deux cohabitent).
Deux racks sur le même canal = les deux sons superposés.

### CC reconnus par les racks

| CC | Effet | CC | Effet |
|---|---|---|---|
| 7 | LEVEL | 12 | DRIVE |
| 10 | PAN | 13 | CRUSH |
| 74 | macro 1 | 93 | DELAY |
| 71 | macro 2 | 91 | REVERB |
| 73 | macro 3 | 1 | modulation (vibrato) |
| 72 | macro 4 | 120 / 123 | tout couper |

Pitch bend : ± 2 demi-tons. Les boutons par défaut du contrôleur (CUTOFF 74, RESO 71, ATTACK 73,
RELEASE 72, REVERB 91, CHORUS 93…) pilotent donc directement les macros et les effets.

---

## 4. REC

Bouton **REC** dans le bandeau du haut, sur toutes les pages :
1er appui = l'enregistrement démarre (le bouton passe en accent, avec le temps écoulé) ;
2e appui = arrêt, une fenêtre propose de **nommer le morceau**, puis de choisir où enregistrer le fichier
(WAV 16 bits stéréo). REC capte les synthés internes (pas le son d'un instrument externe).

---

## 5. Sécurité

- Aucune permission Internet. Bluetooth uniquement pour le MIDI.
- La clé de signature n'est jamais dans le dépôt : elle vient des secrets GitHub.
- Le chargement d'une sauvegarde est limité à 8 Mo et vérifie le format.

## Licences

- Code : écrit pour ce projet.
- Polices Space Mono (SIL Open Font License 1.1), Aurebesh SilvinoR (SIL OFL 1.1).
