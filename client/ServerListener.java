package client;

import java.io.*;
import java.net.*;
import java.util.Map;

/**
 * ServerListener — Thread d'écoute passive du serveur.
 *
 * Rôle :
 * - Lit en continu les messages envoyés par le serveur sur le socket TCP.
 * - Décode chaque message selon le protocole GG et délègue le traitement
 *   à P2PManager (pour GAME_STARTED) ou notifie CLIHandler des événements.
 *
 * Hypothèses :
 * - Chaque message du serveur est sur une ligne distincte (terminé par \n).
 * - Les champs d'un message sont séparés par '|'.
 * - Un message inconnu est affiché et ignoré sans planter le thread.
 * - La fermeture du socket (disconnect) provoque la fin naturelle de la boucle.
 */
public class ServerListener extends Thread {

    // ── Champs ──────────────────────────────────────────────────────────────

    /** Flux d'entrée depuis le socket serveur. */
    private final BufferedReader in;

    /** Référence vers le client principal pour accéder à P2PManager et CLIHandler. */
    private final GGClient client;

    /** Contrôle de la boucle principale. */
    private volatile boolean running = true;

    // ── Constructeur ────────────────────────────────────────────────────────

    /**
     * @param serverSocket Socket TCP déjà connecté au serveur.
     * @param client       Instance de GGClient coordonnatrice.
     */
    public ServerListener(Socket serverSocket, GGClient client) throws IOException {
        super("ServerListener");
        this.client = client;
        this.in     = new BufferedReader(
                new InputStreamReader(serverSocket.getInputStream(), "UTF-8"));
        setDaemon(true); // S'arrête automatiquement si le thread principal se termine
    }

    // ── Boucle principale ────────────────────────────────────────────────────

    @Override
    public void run() {
        System.out.println("[ServerListener] En écoute du serveur...");
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                handleMessage(line.trim());
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("[ServerListener] Connexion perdue : " + e.getMessage());
            }
        } finally {
            System.out.println("[ServerListener] Thread terminé.");
            running = false;
            // Notifie le CLIHandler que la connexion est fermée
            CLIHandler cli = client.getCLIHandler();
            if (cli != null) cli.interrupt();
        }
    }

    // ── Traitement des messages ──────────────────────────────────────────────

    /**
     * Analyse un message reçu du serveur et déclenche l'action appropriée.
     *
     * @param rawMessage Le message brut reçu (ex. "GG|CONNECTED|Alice")
     */
    private void handleMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) return;

        String[] parts = rawMessage.split("\\|");

        // Validation du préfixe GG
        if (parts.length < 2 || !parts[0].equals("GG")) {
            System.out.println("[ServerListener] Message non-GG ignoré : " + rawMessage);
            return;
        }

        String type = parts[1];

        switch (type) {

            // ── Connexion ────────────────────────────────────────────────
            case "CONNECTED":
                // GG|CONNECTED|nom_joueur
                onConnected(parts);
                break;

            case "ERROR":
                // GG|ERROR|raison
                onError(parts);
                break;

            // ── Salles ──────────────────────────────────────────────────
            case "ROOM_CREATED":
                // GG|ROOM_CREATED|nom_salle
                onRoomCreated(parts);
                break;

            case "ROOM_LIST":
                // GG|ROOM_LIST|salle1,salle2,...
                onRoomList(parts);
                break;

            case "JOINED_ROOM":
                // GG|JOINED_ROOM|nom_salle|liste_joueurs
                onJoinedRoom(parts);
                break;

            case "LEFT_ROOM":
                // GG|LEFT_ROOM|nom_salle
                onLeftRoom(parts);
                break;

            case "PLAYER_KICKED":
                // GG|PLAYER_KICKED|nom_joueur
                onPlayerKicked(parts);
                break;

            // ── Démarrage de partie ──────────────────────────────────────
            case "GAME_STARTED":
                // GG|GAME_STARTED|nom_salle|liste_joueurs
                onGameStarted(rawMessage);
                break;

            case "SERVER_GAME_STARTED":
                // GG|SERVER_GAME_STARTED|tentatives
                onServerGameStarted(parts);
                break;

            case "INFO":
                // GG|INFO|message
                onInfo(parts);
                break;

            case "FEEDBACK":
                // GG|FEEDBACK|couleurs_correctes|positions_correctes
                onServerFeedback(parts);
                break;

            case "WINNER":
                // GG|WINNER|nom_joueur
                onServerWinner(parts);
                break;

            // ── Nouveau jeu ──────────────────────────────────────────────
            case "NEW_GAME":
                onNewGame();
                break;

            case "GAME_OVER":
                onGameOver(parts);
                break;

            // ── Cas inconnu ──────────────────────────────────────────────
            default:
                System.out.println("[ServerListener] Type de message inconnu : " + type);
        }
    }

    // ── Handlers individuels ─────────────────────────────────────────────────

    /** Le serveur confirme la connexion du joueur. */
    private void onConnected(String[] parts) {
        String name = (parts.length > 2) ? parts[2] : "?";
        System.out.println("[ServerListener] Connexion confirmée pour : " + name);
        client.setPlayerName(name);
        client.setConnected(true);

        // Notifie le CLIHandler pour afficher l'invite principale
        CLIHandler cli = client.getCLIHandler();
        if (cli != null) cli.printPrompt();
    }

    /** Le serveur signale une erreur. */
    private void onError(String[] parts) {
        String reason = (parts.length > 2) ? parts[2] : "raison inconnue";
        System.out.println("[ServerListener] ERREUR serveur : " + reason);

        if (!client.isConnected()) {
            client.setPlayerName("[NON_CONNECTE]");
            client.setConnected(false);
        }

        CLIHandler cli = client.getCLIHandler();
        if (cli != null) cli.printPrompt();
    }

    /** Le serveur envoie un message d'information. */
    private void onInfo(String[] parts) {
        String info = (parts.length > 2) ? parts[2] : "(aucune information)";
        System.out.println("[ServerListener] INFO : " + info);
    }

    /** Le serveur confirme la création d'une salle. */
    private void onRoomCreated(String[] parts) {
        String room = (parts.length > 2) ? parts[2] : "?";
        System.out.println("[ServerListener] Salle créée : " + room);
    }

    /**
     * Le serveur envoie la liste des salles disponibles.
     * Chaque salle est affichée sur une ligne distincte (requis par l'énoncé).
     */
    private void onRoomList(String[] parts) {
        if (parts.length < 3 || parts[2].isEmpty()) {
            System.out.println("[ServerListener] Aucune salle disponible.");
            return;
        }
        String[] rooms = parts[2].split(",");
        System.out.println("[ServerListener] Salles disponibles (" + rooms.length + ") :");
        for (String room : rooms) {
            System.out.println("  - " + room.trim());
        }
    }

    /**
     * Le joueur a rejoint une salle. Établit les connexions P2P avec les autres joueurs.
     * Hypothèse : liste_joueurs est une chaîne "joueur1,joueur2,..."
     *             chaque joueur expose un port P2P = port_serveur + hash simplifié.
     *             La gestion fine du port P2P est déléguée à P2PManager.
     */
    private void onJoinedRoom(String[] parts) {
        String room    = (parts.length > 2) ? parts[2] : "?";
        String players = (parts.length > 3) ? parts[3] : "";
        System.out.println("[ServerListener] Rejoint la salle : " + room);
        if (!players.isEmpty()) {
            String[] playerList = players.split(",");
            System.out.println("[ServerListener] Joueurs dans la salle :");
            for (String p : playerList) {
                System.out.println("  - " + p.trim());
            }
        }
        // Connexion P2P différée jusqu'à GAME_STARTED (voir onGameStarted)
    }

    /** Le joueur a quitté la salle. */
    private void onLeftRoom(String[] parts) {
        String room = (parts.length > 2) ? parts[2] : "?";
        System.out.println("[ServerListener] Vous avez quitté la salle : " + room);

        P2PManager p2p = client.getP2PManager();
        if (p2p != null) {
            p2p.close();
            client.setPlayingServerGame(false);
            System.out.println("[ServerListener] P2P fermé après sortie de la salle.");
        }

        // Recréer un P2PManager avec un nouveau ServerSocket pour pouvoir jouer dans une autre salle
        P2PManager newP2p = new P2PManager(client.getPlayerName(), client);
        try {
            newP2p.startListening(0);
            System.out.println("[ServerListener] Nouveau serveur P2P prêt sur le port " + newP2p.getListeningPort());
        } catch (java.io.IOException e) {
            System.err.println("[ServerListener] Impossible de redémarrer le serveur P2P : " + e.getMessage());
        }
        client.setP2PManager(newP2p);

        CLIHandler cli = client.getCLIHandler();
        if (cli != null) cli.printPrompt();
    }

    /**
     * Un joueur a été expulsé.
     * Si c'est le joueur local, on retourne à l'interface principale.
     */
    private void onPlayerKicked(String[] parts) {
        String kicked = (parts.length > 2) ? parts[2] : "?";
        System.out.println("[ServerListener] Joueur expulsé : " + kicked);
        if (kicked.equals(client.getPlayerName())) {
            System.out.println("[ServerListener] Vous avez été expulsé ! Retour au menu principal.");
            P2PManager p2p = client.getP2PManager();
            if (p2p != null) {
                p2p.close();
                client.setPlayingServerGame(false);
                System.out.println("[ServerListener] P2P fermé suite à l'expulsion.");
            }
            P2PManager newP2p = new P2PManager(client.getPlayerName(), client);
            try {
                newP2p.startListening(0);
                System.out.println("[ServerListener] Nouveau serveur P2P prêt sur le port " + newP2p.getListeningPort());
            } catch (java.io.IOException e) {
                System.err.println("[ServerListener] Impossible de redémarrer le serveur P2P : " + e.getMessage());
            }
            client.setP2PManager(newP2p);

            CLIHandler cli = client.getCLIHandler();
            if (cli != null) cli.printPrompt();
        }
    }

    /**
     * La partie démarre — délègue à P2PManager pour établir les connexions pair-à-pair.
     * GG|GAME_STARTED|nom_salle|joueur1:ip1:port1,joueur2:ip2:port2,...
     *
     * Hypothèse : le serveur encode les adresses P2P dans la liste_joueurs sous la forme
     *             nom:ip:port pour chaque joueur. P2PManager sait les parser.
     */
    private void onGameStarted(String rawMessage) {
        System.out.println("[ServerListener] Partie démarrée ! Initialisation P2P...");
        System.out.println("[ServerListener] Astuce : si vous êtes admin, définissez le secret avec 'secret c1 c2 c3 c4'.");
        String[] parts = rawMessage.split("\\|");
        int attempts = 0;
        String admin = null;
        if (parts.length > 3) {
            try {
                attempts = Integer.parseInt(parts[3]);
            } catch (NumberFormatException ignored) {
                attempts = 0;
            }
        }
        if (parts.length > 4) {
            admin = parts[4];
        }
        if (attempts > 0) {
            System.out.println("[ServerListener] Tentatives par joueur : " + attempts);
        }
        if (admin != null && !admin.isBlank()) {
            System.out.println("[ServerListener] Admin de la salle : " + admin);
        }
        P2PManager p2p = client.getP2PManager();
        if (p2p != null) {
            p2p.resetForNewGame();
            if (attempts > 0) {
                p2p.getGameEngine().setMaxAttempts(attempts);
            }
            if (admin != null) {
                p2p.setRoomAdminName(admin);
            }
            Map<String, String> peerAddresses = parseAddresses(rawMessage);
            p2p.connectToPeers(peerAddresses);
            // Set players list for turns (admin inclus par défaut, sera retiré si Cas 2)
            java.util.Set<String> playersSet = new java.util.LinkedHashSet<>(peerAddresses.keySet());
            playersSet.add(client.getPlayerName());
            java.util.List<String> players = new java.util.ArrayList<>(playersSet);
            p2p.setPlayersList(players);
        }
    }

    /**
     * Partie contre le serveur démarrée.
     * GG|SERVER_GAME_STARTED|tentatives
     */
    private void onServerGameStarted(String[] parts) {
        int attempts = (parts.length > 2) ? Integer.parseInt(parts[2]) : 10;
        System.out.println("[ServerListener] Partie contre le serveur démarrée. Tentatives : " + attempts);

        P2PManager p2p = client.getP2PManager();
        if (p2p != null) {
            p2p.getGameEngine().setMaxAttempts(attempts);
        }
        client.setPlayingServerGame(true);
    }

    /**
     * Feedback de la partie solo (serveur -> client).
     */
    private void onServerFeedback(String[] parts) {
        if (parts.length < 4) {
            System.err.println("[ServerListener] FEEDBACK mal formé.");
            return;
        }
        System.out.println("[GAME] Feedback : " + parts[2] + " couleur(s) correcte(s), " + parts[3] + " position(s) correcte(s).");
        P2PManager p2p = client.getP2PManager();
        int attLeft = (p2p != null) ? p2p.getGameEngine().getAttemptsLeft() : 0;
        if (attLeft > 0) {
            System.out.println("[GAME] Tentatives restantes : " + attLeft);
        } else {
            System.out.println("[GAME] Plus de tentatives. En attente du résultat...");
        }
    }

    /**
     * Annonce du gagnant pour la partie solo (serveur -> client).
     */
    private void onServerWinner(String[] parts) {
        // La victoire sera annoncée par GAME_OVER — pas de doublon ici
        client.setPlayingServerGame(false);
    }

    /** Nouveau jeu dans la salle — réinitialise l'état local. */
    private void onNewGame() {
        System.out.println("[ServerListener] Nouveau jeu ! Réinitialisation de l'état local.");
        P2PManager p2p = client.getP2PManager();
        if (p2p != null) p2p.resetForNewGame();
    }

    /**
     * Partie solo terminée (ou fin de partie en salle si jamais propagated).
     * GG|GAME_OVER|WIN|joueur ou GG|GAME_OVER|LOSE|NONE
     */
    private void onGameOver(String[] parts) {
        String result = (parts.length > 2) ? parts[2] : "UNKNOWN";
        String winner = (parts.length > 3) ? parts[3] : "NONE";

        if ("WIN".equalsIgnoreCase(result)) {
            System.out.println("[GAME] ★★★ Partie terminée — victoire de " + winner + " ! ★★★");
        } else if ("LOSE".equalsIgnoreCase(result)) {
            System.out.println("[GAME] Partie terminée — personne n'a trouvé le secret.");
        } else {
            System.out.println("[GAME] Partie terminée.");
        }

        client.setPlayingServerGame(false);
    }

    /**
     * Parse les adresses P2P depuis le message GAME_STARTED.
     * Format attendu (hypothèse d'équipe) : ...| nom1:ip1:port1,nom2:ip2:port2
     *
     * @return Un tableau de type {"nom:ip:port", ...}
     */
    private java.util.Map<String, String> parseAddresses(String rawMessage) {
        java.util.Map<String, String> addresses = new java.util.HashMap<>();
        String[] parts = rawMessage.split("\\|");
        if (parts.length < 4) return addresses;

        String payload;
        if (parts.length >= 6) {
            payload = parts[5];
        } else if (parts.length >= 5) {
            payload = parts[4];
        } else {
            payload = parts[3];
        }

        // payload = "joueur1:ip1:port1,joueur2:ip2:port2"
        String[] entries = payload.split(",");
        for (String entry : entries) {
            String[] tokens = entry.trim().split(":");
            if (tokens.length == 3) {
                // clé = nom, valeur = ip:port
                addresses.put(tokens[0], tokens[1] + ":" + tokens[2]);
            }
        }
        return addresses;
    }

    // ── Arrêt propre ─────────────────────────────────────────────────────────

    /** Arrête proprement la boucle du thread. */
    public void stopListening() {
        running = false;
        interrupt();
    }
}