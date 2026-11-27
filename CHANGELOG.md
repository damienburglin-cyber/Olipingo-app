# Olipingo app changes

The newest version comes first. The release workflow copies the section of the version it
publishes into the release notes, which the app shows in its update dialog.

## 0.2.2
Tu peux fermer Olipingo pendant une marche : le compteur de pas du téléphone continue tout seul, et quand tu rouvres l'appli, les pas faits entre-temps sont ajoutés à ta marche. Les minutes de ces pas sont estimées (environ 100 pas par minute). Et quand Olipingo est en arrière-plan, la page se met en pause : moins de batterie.

## 0.2.1
L’écran du podomètre en direct : pendant une marche, Olipingo reçoit tes pas minute par minute et peut te montrer ce qu’il enregistre (pas, minutes actives, la minute en cours). Rien ne change pour la batterie : les pas sont toujours lus toutes les 20 secondes.

## 0.2.0
Le podomètre : « Je pars marcher » lance le compteur de pas du téléphone, une notification compte avec toi et « Arrêter » termine la marche. Elle s'ajoute à ta journée comme une séance « Dehors » (minutes où tu marches vraiment, pas, km). Il ne tourne que quand tu le lances, et s'arrête seul après une heure sans pas. La première fois, Android demande l'accès à l'activité physique et aux notifications ; sur un téléphone Xiaomi, mets Olipingo en batterie « Pas de restriction ».

## 0.1.6
Retour de Foulée sans geste : en revenant dans Olipingo, la séance copiée par Foulée s'importe toute seule. L'appli ne lit dans le presse-papier que le code de séance, rien d'autre. S'il n'y a pas de code, le curseur se place dans le cadre et le clavier s'ouvre pour coller.

## 0.1.5
Restaurer une sauvegarde marche depuis l'appli : le bouton « Choisir un fichier » ouvre bien le sélecteur de fichiers, où l'on retrouve le fichier olipingo-sauvegarde-….json dans Téléchargements.

## 0.1.4
Nouvelle icône : les trois anneaux d'Olipingo (marche, renfo, repas) sur fond sable, aussi en icône à thème. Elle accompagne la nouvelle allure de l'appli, « Argile ».

## 0.1.3
Sauvegarde depuis le téléphone : dans Olipingo, « Sauvegarder mes données » propose « Enregistrer sur le téléphone » et range le fichier dans Téléchargements. « Copier mes données » passe aussi par l'appli.

## 0.1.2
Mises à jour plus sages : l'appli ouvre l'écran d'installation d'Android au lieu d'installer elle-même, comme Echo, pour que Play Protect ne la signale plus. Elle cherche une nouvelle version quand tu reviens dessus après 10 minutes (au lieu de 30).

## 0.1.1
Une photo choisie dans la galerie est bien envoyée pour l'analyse (les photos HEIC sont converties en JPEG). Le bouton « Envoyer à Olipingo » de Foulée ouvre directement l'appli.

## 0.1.0
Première version : Olipingo en plein écran, la connexion à Claude qui tient, une photo de repas avec l'appareil photo ou la galerie, Foulée et WhatsApp qui s'ouvrent dans leur appli, et les mises à jour depuis l'appli.
