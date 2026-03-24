package client;

import common.Color;
import common.CommandType;
import common.DebugLogger;
import common.MessageParser;
import common.ParseException;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;



/**
 * P2PManager — gestionnaire des connexions directes entre clients pendant une partie.
 *
 * Responsabilités :
 *  - Ouvrir un ServerSocket P2P si ce client est l'hôte de la salle (créateur).
 *  - Établir des connexions TCP sortantes vers chaque pair listés dans GAME_STARTED.
 *  - Diffuser les messages de jeu (GUESS, FEEDBACK, WINNER, SECRET_SET, NEW_GAME).
 *  - Déléguer le traitement des messages P2P entrants à PeerListener (un thread par pair).
 *  - Se réinitialiser à chaque NEW_GAME.
 *
 * Hypothèses :
 *  - Le créateur de la salle est l'hôte P2P : il ouvre le ServerSocket en premier.
 *  - Les adresses des pairs arrivent dans GAME_STARTED au format nom:ip:port,nom:ip:port,...
 *  - Le GameEngine est créé ici et partagé avec les PeerListeners.
 *  - Si un pair se déconnecte en cours de partie, la partie est annulée
 *    (gestion dans PeerListener via IOException).
 *
 * Compatibilité :
 *  - Constructeur à 1 argument (playerName) pour correspondre à GGClient de Thomas.
 *  - sendGuess(List<String>) pour correspondre à CLIHandler de Thomas.
 *  - sendFeedback(int, int) pour correspondre à CLIHandler de Thomas.
 */
public class P2PManager {

    // -------------------------------------------------------------------------
    // Attributs
    // -------------------------------------------------------------------------

    /** ServerSocket P2P — ouvert uniquement si ce client est l'hôte. */
    private ServerSocket p2pServerSocket;

    /** Sockets vers chaque pair, indexés par nom de joueur. */
    private final ConcurrentHashMap<String, Socket> peers = new ConcurrentHashMap<>();

    /** Un PeerListener (thread) par pair connecté. */
    private final List<PeerListener> peerListeners = new ArrayList<>();

    /** Logique de jeu locale — partagée avec les PeerListeners. */
    private final GameEngine gameEngine;

    /** Nom de ce client (pour construire les messages GG). */
    private final String playerName;

    /**
     * Nom du détenteur du secret pour la manche courante.
     * Mis à jour par PeerListener à la réception de SECRET_SET.
     */
    private volatile String currentSecretOwner = null;

    private final DebugLogger logger = DebugLogger.getInstance();

    // -------------------------------------------------------------------------
    // Constructeurs
    // -------------------------------------------------------------------------

    /**
     * Constructeur principal utilisé par GGClient (Thomas).
     * Le GameEngine est créé ici avec un nombre de tentatives par défaut.
     *
     * @param playerName nom de ce client sur le réseau
     */
    public P2PManager(String playerName) {
        this.playerName  = playerName;
        this.gameEngine  = new GameEngine();
    }

    /**
     * Constructeur alternatif permettant d'injecter un GameEngine existant.
     * Utile pour les tests unitaires.
     *
     * @param playerName nom de ce client sur le réseau
     * @param gameEngine instance partagée du moteur de jeu
     */
    public P2PManager(String playerName, GameEngine gameEngine) {
        this.playerName = playerName;
        this.gameEngine = gameEngine;
    }

    // -------------------------------------------------------------------------
    // Démarrage en mode hôte
    // -------------------------------------------------------------------------

    /**
     * Ouvre un ServerSocket sur le port donné et attend les connexions entrantes.
     * Chaque connexion acceptée lance un PeerListener dans un thread daemon.
     * Appelé uniquement par le créateur de la salle.
     *
     * @param port port d'écoute P2P
     * @throws IOException si le port est déjà utilisé ou inaccessible
     */
    public void startListening(int port) throws IOException {
        p2pServerSocket = new ServerSocket(port);
        logger.logEvent("P2PManager : écoute P2P démarrée sur le port " + port);

        Thread acceptThread = new Thread(() -> {
            while (!p2pServerSocket.isClosed()) {
                try {
                    Socket peerSocket = p2pServerSocket.accept();
                    logger.logEvent("P2PManager : pair entrant depuis "
                            + peerSocket.getInetAddress().getHostAddress());
                    PeerListener listener = new PeerListener(peerSocket, this, gameEngine);
                    peerListeners.add(listener);
                    listener.start();
                } catch (IOException e) {
                    if (!p2pServerSocket.isClosed()) {
                        logger.logError("P2PManager : erreur acceptation pair.", e);
                    }
                }
            }
        }, "P2P-AcceptThread");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    // -------------------------------------------------------------------------
    // Connexion vers les pairs
    // -------------------------------------------------------------------------

    /**
     * Établit des connexions TCP sortantes vers tous les pairs listés dans GAME_STARTED.
     * Appelé par ServerListener de Thomas à la réception de GAME_STARTED.
     *
     * @param peerAddresses map nom → "ip:port" pour chaque pair
     *                      (ne doit pas contenir ce client lui-même)
     */
    public void connectToPeers(Map<String, String> peerAddresses) {
        for (Map.Entry<String, String> entry : peerAddresses.entrySet()) {
            String peerName = entry.getKey();

            // Ne pas se connecter à soi-même
            if (peerName.equals(playerName)) continue;

            String[] parts = entry.getValue().split(":");
            if (parts.length != 2) {
                logger.logError("P2PManager : adresse invalide pour " + peerName + " : " + entry.getValue());
                continue;
            }
            String ip  = parts[0];
            int    port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                logger.logError("P2PManager : port invalide pour " + peerName, e);
                continue;
            }

            try {
                Socket socket = new Socket(ip, port);
                peers.put(peerName, socket);
                logger.logEvent("P2PManager : connecté au pair " + peerName + " (" + ip + ":" + port + ")");
                PeerListener listener = new PeerListener(socket, this, gameEngine);
                peerListeners.add(listener);
                listener.start();
            } catch (IOException e) {
                logger.logError("P2PManager : impossible de se connecter à "
                        + peerName + " (" + ip + ":" + port + ")", e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Envoi de messages P2P
    // -------------------------------------------------------------------------

    /**
     * Diffuse un message brut à tous les pairs connectés.
     * Appelé par CLIHandler (Thomas) pour SECRET_SET, WINNER, NEW_GAME.
     *
     * @param rawMessage message GG sérialisé (ex: "GG|NEW_GAME")
     */
    public synchronized void broadcast(String rawMessage) {
        logger.logOutgoingRaw(rawMessage);
        for (String peerName : peers.keySet()) {
            sendToPeer(peerName, rawMessage);
        }
    }

    /**
     * Envoie une proposition (GUESS) au détenteur du secret courant.
     * Signature compatible avec CLIHandler de Thomas : accepte List<String>.
     *
     * Hypothèse : la liste contient exactement 4 chaînes correspondant
     * aux noms de couleurs (ex: ["RED", "BLUE", "GREEN", "YELLOW"]).
     *
     * @param colorNames liste de 4 noms de couleurs en String
     */
    public void sendGuess(List<String> colorNames) {
        if (colorNames == null || colorNames.size() != GameEngine.COMBINATION_SIZE) {
            logger.logError("P2PManager : sendGuess — liste invalide (" + colorNames + ")");
            return;
        }

        // Conversion String → Color pour validation
        List<Color> colors = new ArrayList<>();
        for (String name : colorNames) {
            try {
                colors.add(Color.fromString(name));
            } catch (ParseException e) {
                logger.logError("P2PManager : couleur invalide dans sendGuess : " + name, e);
                return;
            }
        }

        // Construction du message GG|GUESS|c1|c2|c3|c4
        StringBuilder sb = new StringBuilder("GG|GUESS");
        for (Color c : colors) {
            sb.append("|").append(c.name());
        }
        String msg = sb.toString();
        logger.logOutgoingRaw(msg);

        // Envoi au détenteur du secret
        if (currentSecretOwner != null && peers.containsKey(currentSecretOwner)) {
            sendToPeer(currentSecretOwner, msg);
        } else {
            // Fallback : diffusion à tous si le détenteur n'est pas encore connu
            logger.logEvent("P2PManager : détenteur inconnu, diffusion du GUESS à tous.");
            broadcast(msg);
        }
    }

    /**
     * Surcharge de sendGuess pour la compatibilité interne avec Color directement.
     *
     * @param guess       liste de Color
     * @param secretOwner nom du détenteur du secret
     */
    public void sendGuess(List<Color> guess, String secretOwner) {
        if (guess == null || guess.size() != GameEngine.COMBINATION_SIZE) {
            logger.logError("P2PManager : proposition invalide.");
            return;
        }
        StringBuilder sb = new StringBuilder("GG|GUESS");
        for (Color c : guess) sb.append("|").append(c.name());
        String msg = sb.toString();
        logger.logOutgoingRaw(msg);
        sendToPeer(secretOwner, msg);
    }

    /**
     * Envoie un feedback (FEEDBACK) à un pair.
     * Signature compatible avec CLIHandler de Thomas : accepte (int, int).
     *
     * Hypothèse : appelé manuellement depuis le CLI par le détenteur du secret.
     * En jeu automatique, c'est PeerListener qui appelle sendFeedback(String, Feedback).
     *
     * @param correctColors    nombre de couleurs correctes mal placées
     * @param correctPositions nombre de positions correctes
     */
    public void sendFeedback(int correctColors, int correctPositions) {
        Feedback feedback = new Feedback(correctColors, correctPositions);
        String msg = feedback.toGGString();
        logger.logOutgoingRaw(msg);

        // Diffusion à tous (on ne sait pas qui a posé la question depuis le CLI)
        broadcast(msg);

        // Si victoire, diffuser WINNER
        if (feedback.isWin()) {
            // Hypothèse : on diffuse WINNER sans nom précis depuis le CLI
            broadcast("GG|WINNER|" + playerName);
        }
    }

    /**
     * Surcharge pour l'envoi ciblé depuis PeerListener.
     *
     * @param guesserName nom du joueur destinataire
     * @param feedback    objet Feedback calculé par GameEngine
     */
    public void sendFeedback(String guesserName, Feedback feedback) {
        String msg = feedback.toGGString();
        logger.logOutgoingRaw(msg);
        sendToPeer(guesserName, msg);

        if (feedback.isWin()) {
            String winnerMsg = MessageParser.serialize(CommandType.WINNER, guesserName);
            broadcast(winnerMsg);
        }
    }

    /**
     * Annonce que ce client a choisi son secret (GG|SECRET_SET|nom_joueur).
     * Diffusé à tous les pairs.
     */
    public void announceSecretSet() {
        String msg = MessageParser.serialize(CommandType.SECRET_SET, playerName);
        broadcast(msg);
    }

    /**
     * Réinitialise l'état P2P et le GameEngine pour une nouvelle manche.
     * Appelé par ServerListener de Thomas à la réception de NEW_GAME.
     * NE diffuse PAS NEW_GAME (c'est CLIHandler qui le fait via broadcast).
     */
    public void resetForNewGame() {
        gameEngine.reset();
        currentSecretOwner = null;
        logger.logEvent("P2PManager : réinitialisé pour nouvelle manche.");
    }

    // -------------------------------------------------------------------------
    // Enregistrement d'un pair (appelé par PeerListener)
    // -------------------------------------------------------------------------

    /**
     * Enregistre le socket d'un pair identifié par son nom.
     * Appelé par PeerListener quand il identifie le pair depuis le premier message.
     */
    public void registerPeer(String peerName, Socket peerSocket) {
        peers.put(peerName, peerSocket);
        logger.logEvent("P2PManager : pair enregistré : " + peerName);
    }

    /**
     * Met à jour le détenteur du secret courant.
     * Appelé par PeerListener à la réception de SECRET_SET.
     *
     * @param ownerName nom du joueur qui détient le secret
     */
    public void setCurrentSecretOwner(String ownerName) {
        this.currentSecretOwner = ownerName;
        logger.logEvent("P2PManager : détenteur du secret mis à jour : " + ownerName);
    }

    // -------------------------------------------------------------------------
    // Fermeture
    // -------------------------------------------------------------------------

    /**
     * Ferme toutes les connexions P2P et le ServerSocket.
     */
    public void close() {
        for (Map.Entry<String, Socket> entry : peers.entrySet()) {
            try { entry.getValue().close(); }
            catch (IOException e) {
                logger.logError("P2PManager : erreur fermeture pair " + entry.getKey(), e);
            }
        }
        peers.clear();
        peerListeners.clear();

        if (p2pServerSocket != null && !p2pServerSocket.isClosed()) {
            try { p2pServerSocket.close(); }
            catch (IOException e) {
                logger.logError("P2PManager : erreur fermeture ServerSocket.", e);
            }
        }
        logger.logEvent("P2PManager : toutes les connexions P2P fermées.");
    }

    // -------------------------------------------------------------------------
    // Méthode interne
    // -------------------------------------------------------------------------

    private void sendToPeer(String peerName, String rawMessage) {
        Socket socket = peers.get(peerName);
        if (socket == null || socket.isClosed()) {
            logger.logError("P2PManager : pair introuvable ou déconnecté : " + peerName);
            return;
        }
        try {
            socket.getOutputStream().write((rawMessage + "\n").getBytes("UTF-8"));
            socket.getOutputStream().flush();
        } catch (IOException e) {
            logger.logError("P2PManager : erreur d'envoi vers " + peerName, e);
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public GameEngine getGameEngine()        { return gameEngine; }
    public String     getPlayerName()        { return playerName; }
    public String     getCurrentSecretOwner(){ return currentSecretOwner; }
}
