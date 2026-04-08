# 🎨 Guess Game — Protocole GG

> Un jeu de devinette de couleurs en réseau développé dans le cadre du cours **6GEN723 — Réseaux d'ordinateurs** à l'UQAC.  
> Coordination client–serveur via TCP, jeu en pair-à-pair, et protocole applicatif personnalisé.

---

## 📌 Table des matières

- [Aperçu](#aperçu)
- [Architecture](#architecture)
- [Structure du projet](#structure-du-projet)
- [Référence du protocole GG](#référence-du-protocole-gg)
  - [Commandes serveur](#commandes-serveur)
  - [Commandes client–client (P2P)](#commandes-clientclient-p2p)
- [Démarrage rapide](#démarrage-rapide)
  - [Prérequis](#prérequis)
  - [Compilation](#compilation)
  - [Lancer le serveur](#lancer-le-serveur)
  - [Lancer un client](#lancer-un-client)
- [Règles du jeu](#règles-du-jeu)
- [Décisions de conception et hypothèses](#décisions-de-conception-et-hypothèses)
- [Description des classes](#description-des-classes)
- [Journalisation et traces](#journalisation-et-traces)
- [Fonctionnalités bonus](#fonctionnalités-bonus)
- [Membres de l'équipe](#membres-de-léquipe)

---

## Aperçu

**Guess Game** est un jeu multijoueur de devinette de combinaisons de couleurs.  
Un joueur choisit une combinaison secrète de **4 couleurs** (parmi Rouge, Vert, Bleu, Jaune, Orange).  
Les autres joueurs tentent de la deviner. Après chaque proposition, le détenteur du secret répond avec :

- Le nombre de **couleurs correctes** (bonne couleur, mauvaise position)
- Le nombre de **positions correctes** (bonne couleur, bonne position)

Le premier joueur à deviner la combinaison exacte **gagne la partie**.

Le jeu repose sur une **architecture hybride client–serveur et pair-à-pair** :
- Le **serveur** gère le lobby (salles, joueurs, sessions).
- Une fois la partie lancée, les clients communiquent **directement entre eux (P2P)** via TCP.
- Les joueurs peuvent également jouer **contre le serveur** en mode solo.

---

## Architecture

```
┌──────────────┐        TCP (Protocole GG)        ┌──────────────────────┐
│   Client A   │ ──────────────────────────────► │                      │
│              │                                  │       SERVEUR        │
│   Client B   │ ──────────────────────────────► │  (Hub de coordination)│
│              │                                  │                      │
│   Client C   │ ──────────────────────────────► │                      │
└──────┬───────┘                                  └──────────────────────┘
       │
       │   TCP direct (P2P — pendant la partie)
       ▼
┌──────────────┐
│  Client B/C  │   ◄──── GG|GUESS / GG|FEEDBACK / GG|WINNER
└──────────────┘
```

**Stratégie P2P :**  
À la réception de `GG|GAME_STARTED`, le créateur de la salle agit comme hôte P2P (ouvre un `ServerSocket`). Les autres clients s'y connectent directement. Tous les messages de jeu (propositions, retours, victoire) transitent par ces connexions directes.

---

## Structure du projet

```
guess-game/
├── common/                       # Code partagé (protocole, utilitaires)
│   ├── Message.java              # Objet immuable représentant un message GG (thread-safe)
│   ├── MessageParser.java        # Parse / sérialise / valide les chaînes GG|...|...
│   ├── CommandType.java          # «enum» de toutes les commandes du protocole
│   ├── Color.java                # «enum» RED, GREEN, BLUE, YELLOW, ORANGE + fromString()
│   └── DebugLogger.java          # Singleton thread-safe — affiche chaque champ sur une ligne
│
├── server/                       # Projet serveur (exporté en server.jar)
│   └── src/
│       └── server/
│           ├── Main.java
│           ├── GGServer.java             # ServerSocket + ExecutorService, accepte N clients
│           ├── ClientHandler.java        # «Thread» — 1 par client, dispatch toutes les commandes
│           ├── Room.java                 # État d'une salle : joueurs, admin, sessions successives
│           ├── GameSession.java          # Session de jeu côté serveur (PLAY_SERVER + historique)
│           ├── SessionStatus.java        # «enum» WAITING / IN_PROGRESS / WON / LOST / ABORTED
│           ├── PermissionManager.java    # Vérifie admin, kick, join, ban
│           ├── SecurityManager.java      # (Bonus) TLS/SSL, HMAC, rate limiting, sanitize
│           ├── GuessRecord.java          # Enregistrement immuable d'une proposition
│           └── Feedback.java             # Résultat d'une proposition (isWin, toGGString)
│
└── client/                       # Projet client (exporté en client.jar)
    └── src/
        └── client/
            ├── Main.java
            ├── GGClient.java             # Point d'entrée — coordonne ServerListener + CLIHandler
            ├── ServerListener.java       # «Thread» — écoute passivement le serveur
            ├── CLIHandler.java           # «Thread» — interface stdin/stdout, envoi libre
            ├── P2PManager.java           # Gère les connexions directes entre pairs
            ├── PeerListener.java         # «Thread» — 1 par pair, gère GUESS/FEEDBACK/WINNER
            ├── GameEngine.java           # Logique de jeu locale : secret, feedback, historique
            ├── GuessRecord.java          # Enregistrement immuable d'une proposition
            └── Feedback.java             # Résultat d'une proposition (isWin, toGGString)
```

> **Important :** Le client et le serveur sont **deux projets distincts** dans l'IDE, chacun exporté sous forme de `.jar` séparé.

---

## Référence du protocole GG

Tous les messages respectent le format suivant :

```
GG|COMMANDE|champ1|champ2|...
```

### Commandes serveur

| Direction | Message | Description |
|-----------|---------|-------------|
| C → S | `GG\|CONNECT\|nom_joueur` | Inscription auprès du serveur |
| S → C | `GG\|CONNECTED\|nom_joueur` | Connexion confirmée |
| C → S | `GG\|CREATE_ROOM\|salle\|max_joueurs\|max_tentatives` | Créer une salle |
| S → C | `GG\|ROOM_CREATED\|salle` | Création de la salle confirmée |
| C → S | `GG\|LIST_ROOMS` | Demander la liste des salles |
| S → C | `GG\|ROOM_LIST\|salle1,salle2,...` | Liste des salles disponibles |
| C → S | `GG\|JOIN_ROOM\|salle` | Rejoindre une salle |
| S → C | `GG\|JOINED_ROOM\|salle\|joueur1,joueur2,...` | Rejointe confirmée + liste des joueurs |
| C → S | `GG\|LEAVE_ROOM\|salle` | Quitter une salle |
| S → C | `GG\|LEFT_ROOM\|salle` | Départ confirmé |
| C → S | `GG\|KICK_PLAYER\|salle\|nom_joueur` | (Admin uniquement) Expulser un joueur |
| S → C | `GG\|PLAYER_KICKED\|nom_joueur` | Joueur expulsé |
| C → S | `GG\|START_GAME\|salle` | Démarrer la partie dans la salle |
| S → C | `GG\|GAME_STARTED\|salle\|joueur1:ip:port,...` | Partie lancée ; informations P2P incluses |
| C → S | `GG\|PLAY_SERVER\|max_tentatives` | Démarrer une partie solo contre le serveur |
| S → C | `GG\|SERVER_GAME_STARTED\|max_tentatives` | Partie solo lancée |

### Commandes client–client (P2P)

| Direction | Message | Description |
|-----------|---------|-------------|
| Diffusion | `GG\|SECRET_SET\|nom_joueur` | Annonce du détenteur du secret |
| C → Détenteur | `GG\|GUESS\|ROUGE\|BLEU\|VERT\|JAUNE` | Soumettre une proposition |
| Détenteur → C | `GG\|FEEDBACK\|couleurs_correctes\|positions_correctes` | Retour sur la proposition |
| Diffusion | `GG\|WINNER\|nom_joueur` | Annonce du gagnant |
| Diffusion | `GG\|NEW_GAME` | Réinitialisation pour une nouvelle manche |

**Le mode solo (contre le serveur) utilise les mêmes messages `GUESS` / `FEEDBACK`**, acheminés vers le serveur plutôt que vers les pairs.

---

## Démarrage rapide

### Prérequis

- **Java 17+** (Java 11 minimum)
- Aucune bibliothèque externe requise (Java SE pur)

### Compilation

Chaque projet est compilé et exporté en `.jar` depuis l'IDE (IntelliJ / Eclipse) :

```bash
# Serveur
javac -d out/server/ server/src/server/*.java
jar cfe server.jar server.Main -C out/server/ .

# Client
javac -d out/client/ client/src/client/*.java
jar cfe client.jar client.Main -C out/client/ .
```

### Lancer le serveur

```bash
java -jar server.jar [port]
# Port par défaut : 5000
# Exemple :
java -jar server.jar 5000
```

### Lancer un client

```bash
java -jar client.jar [ip_serveur] [port_serveur]
# Exemple :
java -jar client.jar 127.0.0.1 5000
```

Une fois connecté, l'interface console accepte **n'importe quel message du protocole GG** à tout moment :

```
> GG|CONNECT|Alice
> GG|CREATE_ROOM|SalleA|4|10
> GG|LIST_ROOMS
> GG|START_GAME|SalleA
> GG|GUESS|ROUGE|BLEU|VERT|JAUNE
```

---

## Règles du jeu

- La combinaison secrète contient **4 couleurs**.
- Couleurs disponibles : `ROUGE`, `VERT`, `BLEU`, `JAUNE`, `ORANGE`
- Les couleurs **peuvent se répéter** (voir hypothèses ci-dessous).
- Après chaque proposition, le détenteur envoie :
  - **Couleurs correctes** — bonne couleur, mauvaise position
  - **Positions correctes** — bonne couleur, bonne position
- Le joueur qui devine exactement la combinaison gagne et envoie `GG|WINNER|nom_joueur`.
- Un message `GG|NEW_GAME` réinitialise la session pour une nouvelle manche.

---

## Décisions de conception et hypothèses

Ces décisions ont été prises par l'équipe pour combler les zones laissées ouvertes par l'énoncé :

| # | Décision | Justification |
|---|----------|---------------|
| 1 | **Les couleurs peuvent se répéter** dans la combinaison secrète | Jeu plus intéressant ; cohérent avec les règles classiques du Mastermind |
| 2 | **Le créateur de la salle est l'hôte P2P** — il ouvre le `ServerSocket` pour les connexions directes | Topologie la plus simple ; évite la complexité d'un maillage complet |
| 3 | **`GAME_STARTED` inclut `ip:port` par joueur** | Les clients ont besoin de ces infos pour ouvrir des sockets directs ; le serveur collecte les ports P2P déclarés à l'entrée dans la salle |
| 4 | **Le détenteur du secret = créateur de la salle** pour la première manche, puis rotation | Rotation équitable ; annoncé via `GG\|SECRET_SET` |
| 5 | **Configuration via arguments CLI** (IP, port) | Approche la plus simple pour l'environnement de démonstration |
| 6 | **Si un client se déconnecte en cours de partie**, la partie est annulée et les joueurs retournent au lobby | Évite les sessions bloquées ; le serveur détecte la coupure via `IOException` |
| 7 | **`KICK_PLAYER` déclenche un `LEFT_ROOM`** pour les autres joueurs | Maintient la liste des joueurs cohérente sur tous les clients |
| 8 | **Le mode solo (contre le serveur)** réutilise le même flux `GUESS`/`FEEDBACK` | Réutilisation propre de la logique de jeu côté client |

> Toutes les hypothèses sont également documentées directement dans les fichiers source concernés.

---

## Description des classes

### Communes (package Common / Protocol)

| Classe | Responsabilité |
|--------|----------------|
| `Message` | Objet immuable et thread-safe représentant un message parsé — construit uniquement par `MessageParser` |
| `MessageParser` | Classe statique utilitaire : parse, sérialise et valide les chaînes `GG|CMD|...` ; délimite par `\|` ; lève `ParseException` si invalide |
| `CommandType` | `«enum»` de toutes les commandes du protocole (CONNECT, CREATE_ROOM, GUESS, FEEDBACK, etc.) |
| `Color` | `«enum»` RED, GREEN, BLUE, YELLOW, ORANGE — avec méthode `fromString()` |
| `DebugLogger` | Singleton thread-safe (`synchronized`) — affiche chaque champ sur une ligne distincte, écrit en stdout et dans un fichier de log |

### Côté serveur

| Classe | Responsabilité |
|--------|----------------|
| `GGServer` | Ouvre le `ServerSocket`, utilise un `ExecutorService` pour accepter N clients simultanément, lance un `ClientHandler` par connexion |
| `ClientHandler` | `«Thread»` — lit et écrit les messages pour un client ; dispatche toutes les `CommandType` (CONNECT, CREATE_ROOM, JOIN_ROOM, KICK_PLAYER, START_GAME, PLAY_SERVER…) |
| `Room` | État d'une salle : liste des joueurs, admin, `maxPlayers`, `maxAttempts`, session courante et historique des sessions |
| `GameSession` | Session de jeu côté serveur : combinaison secrète, propriétaire, tentatives restantes, log des propositions, statut (`SessionStatus`) — utilisée pour le mode PLAY_SERVER |
| `SessionStatus` | `«enum»` WAITING / IN_PROGRESS / WON / LOST / ABORTED |
| `PermissionManager` | Vérifie et gère les permissions : `isAdmin`, `canKick`, `canJoin`, `canStartGame`, `ban`, `isBanned` |
| `SecurityManager` | *(Bonus)* Encapsulation TLS/SSL (`SSLSocket`), signature HMAC des messages, rate limiting par joueur, sanitisation des entrées |
| `GuessRecord` | Enregistrement immuable d'une proposition : couleurs, résultats, nom du joueur, horodatage |
| `Feedback` | Résultat d'une proposition : `correctColors`, `correctPositions`, `isWin()`, `toGGString()` |

### Côté client

| Classe | Responsabilité |
|--------|----------------|
| `GGClient` | Point d'entrée `main()` — ouvre la connexion TCP au serveur, coordonne `ServerListener` et `CLIHandler` |
| `ServerListener` | `«Thread»` — écoute passivement le serveur ; délègue à `P2PManager` si `GAME_STARTED` ; notifie `CLIHandler` des événements |
| `CLIHandler` | `«Thread»` — interface 100% stdin/stdout ; permet l'envoi libre de tout message GG à tout moment ; n'effectue aucune vérification de permission |
| `P2PManager` | Gère toutes les connexions directes entre pairs : `startListening`, `connectToPeers`, `broadcast`, `sendGuess`, `sendFeedback`, `resetForNewGame` |
| `PeerListener` | `«Thread»` — 1 par pair connecté ; gère les messages P2P entrants (SECRET_SET, GUESS, FEEDBACK, WINNER) |
| `GameEngine` | Logique de jeu locale : combinaison secrète, tentatives restantes, historique des propositions, calcul du feedback, `reset()` à chaque NEW_GAME |
| `GuessRecord` | Enregistrement immuable d'une proposition (partagé avec le serveur) |
| `Feedback` | Résultat d'une proposition — `isWin()` détecte la victoire, `toGGString()` formate pour le protocole |

---

## Journalisation et traces

Chaque message envoyé ou reçu est affiché dans la console dans le format suivant (un champ par ligne), comme exigé par l'énoncé :

```
[SERVEUR ←] Type    : GUESS
             Champ 1 : ROUGE
             Champ 2 : BLEU
             Champ 3 : VERT
             Champ 4 : JAUNE

[SERVEUR →] Type    : FEEDBACK
             Champ 1 : 3
             Champ 2 : 1
```

La classe `Logger` centralise ce comportement et est utilisée par le client comme par le serveur.

---

## Fonctionnalités bonus

| Fonctionnalité | État | Notes |
|----------------|------|-------|
| Tests unitaires structurés | ✅ | `GameLogic` et `MessageParser` testés indépendamment |
| Tolérance aux pannes | ✅ | Déconnexion gérée via `IOException` ; partie annulée proprement |
| Chiffrement TLS | 🔄 Prévu | Encapsulation `SSLSocket` dans `SecurityManager` en remplacement du `Socket` classique |

---

## Membres de l'équipe

> Chaque membre complète sa propre ligne ci-dessous.

---

### 👤 Membre 1 — Serveur : connexions et gestion des salles

**Nom :** Simon-Olivier Bolduc

**Classes responsables :**
`P2PMManager` · `PeerListener` · `GameEngine` · `enum de client`

**Travail effectué :**
```
[ Décrivez ici ce que vous avez implémenté, les difficultés rencontrées,
  et les décisions que vous avez prises. Ex : gestion du multithreading via ExecutorService,
  synchronisation des salles avec ConcurrentHashMap, traitement des commandes
  CREATE_ROOM / JOIN_ROOM / KICK_PLAYER, gestion des permissions avec PermissionManager. ]
```

---

### 👤 Membre 2 — Protocole partagé, logging et mode solo

**Nom :** David Carrier

**Classes responsables :**
`GGClient` · `ServerListener` · `CLIHang;er`

**Travail effectué :**
```
[ Décrivez ici ce que vous avez implémenté. Ex : parsing du format GG|...|... dans MessageParser,
  enum CommandType et Color, singleton DebugLogger avec affichage champ par champ,
  logique de GameSession pour le mode PLAY_SERVER, enum SessionStatus. ]
```

---

### 👤 Membre 3 — Client : connexion serveur et interface console

**Nom :** Thomas Bissonette-Royer

**Classes responsables :**
`SecurityManager` · `Room` · `GameSession`

**Travail effectué :**
```
[ Décrivez ici ce que vous avez implémenté. Ex : établissement de la connexion TCP
  vers le serveur dans GGClient, thread ServerListener qui écoute passivement et
  délègue à P2PManager lors de GAME_STARTED, interface CLIHandler permettant
  d'envoyer n'importe quel message GG à tout moment depuis stdin. ]
```

---

### 👤 Membre 4 — P2P et logique de jeu

**Nom :** Émeric Renaud

**Classes responsables :**
`GGServer` · `ClientHandler` · `permissionManager` 

**Travail effectué :**
```
[ Décrivez ici ce que vous avez implémenté. Ex : P2PManager qui gère startListening /
  connectToPeers / broadcast, thread PeerListener par pair pour SECRET_SET / GUESS /
  FEEDBACK / WINNER, GameEngine pour le calcul du feedback et l'historique des propositions
  via GuessRecord et Feedback, reset à chaque NEW_GAME. ]
```

---

> Cours : **6GEN723 — Réseaux d'ordinateurs**, UQAC, Hiver 2026  
> Démonstration : 20–23 avril 2026
