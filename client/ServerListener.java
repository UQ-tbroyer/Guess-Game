package client;

import java.io.*;
import java.net.*;



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
                System.out.println("[ServerListener] ← Serveur : " + line);
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

        // Affichage structuré du type et des champs (requis par l'énoncé)
        System.out.println("[ServerListener] Type    : " + type);
        for (int i = 2; i < parts.length; i++) {
            System.out.println("[ServerListener] Champ " + (i - 1) + " : " + parts[i]);
        }

        switch (type) {

            // ── Connexion ────────────────────────────────────────────────
            case "CONNECTED":
                // GG|CONNECTED|nom_joueur
                onConnected(parts);
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

            // ── Nouveau jeu ──────────────────────────────────────────────
            case "NEW_GAME":
                onNewGame(parts);
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
        // Notifie le CLIHandler pour afficher l'invite principale
        CLIHandler cli = client.getCLIHandler();
        if (cli != null) cli.printPrompt();
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
        P2PManager p2p = client.getP2PManager();
        if (p2p != null) {
            // On passe le message brut ; P2PManager extrait les adresses
            p2p.connectToPeers(parseAddresses(rawMessage));
        }
    }

    /**
     * Partie contre le serveur démarrée.
     * GG|SERVER_GAME_STARTED|tentatives
     */
    private void onServerGameStarted(String[] parts) {
        int attempts = (parts.length > 2) ? Integer.parseInt(parts[2]) : 10;
        System.out.println("[ServerListener] Partie contre le serveur démarrée. Tentatives : " + attempts);
    }

    /** Nouveau jeu dans la salle — réinitialise l'état local. */
    private void onNewGame(String[] parts) {
        System.out.println("[ServerListener] Nouveau jeu ! Réinitialisation de l'état local.");
        P2PManager p2p = client.getP2PManager();
        if (p2p != null) p2p.resetForNewGame();
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

        // parts[3] = "joueur1:ip1:port1,joueur2:ip2:port2"
        String[] entries = parts[3].split(",");
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