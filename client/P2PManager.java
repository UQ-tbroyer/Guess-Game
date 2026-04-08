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
import server.SecurityManager;

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

    /** Thread pour accepter les connexions P2P entrantes. */
    private P2PAcceptor p2pAcceptor;

    /** Port d'écoute P2P (0 = non initialisé). */
    private int listeningPort = 0;

    /** Sockets vers chaque pair, indexés par nom de joueur. */
    private final ConcurrentHashMap<String, Socket> peers = new ConcurrentHashMap<>();

    /** Un PeerListener (thread) par pair connecté. */
    private final List<PeerListener> peerListeners = new ArrayList<>();

    /** Logique de jeu locale — partagée avec les PeerListeners. */
    private final GameEngine gameEngine;

    /** Nom de ce client (pour construire les messages GG). */
    private final String playerName;

    /** Référence optionnelle au client principal pour notifier le serveur. */
    private final GGClient client;

    /** Indicateur si GAME_OVER a été signalé au serveur pour cette manche. */
    private boolean gameOverReportedToServer = false;

    /**
     * Nom du détenteur du secret pour la manche courante.
     * Mis à jour par PeerListener à la réception de SECRET_SET.
     */
    private volatile String currentSecretOwner = null;

    /** Nom de l'admin de la salle pour la manche courante. */
    private volatile String roomAdminName = null;

    /** Vrai si, avant la définition du secret, seul l'admin peut le poser. */
    private volatile boolean secretRestrictedToAdmin = true;

    /** Liste des joueurs dans l'ordre des tours. */
    private volatile List<String> playersInOrder = new ArrayList<>();

    /** Liste complète des joueurs de la manche pour réinitialisation après NEW_GAME. */
    private volatile List<String> initialPlayersInOrder = new ArrayList<>();

    /** Liste de base triée (référence pour recalculer l'ordre mélangé à chaque nouvelle manche). */
    private volatile List<String> basePlayersList = new ArrayList<>();

    /** Compteur de manches — incrémenté à chaque NEW_GAME pour varier le mélange. */
    private volatile int gameCount = 0;

    /** Index du joueur dont c'est le tour. */
    private volatile int currentTurnIndex = 0;

    /** Nombre de joueurs encore actifs (avec des tentatives restantes). */
    private volatile int activePlayersCount = 0;

    /** Dernier joueur annoncé pour éviter les doublons de TURN_ANNOUNCEMENT. */
    private volatile String lastAnnouncedTurnPlayer = null;

    /** Vrai si l'admin est aussi joueur (secret aléatoire, Cas 1). */
    private volatile boolean adminIsPlayer = false;

    private final DebugLogger logger = DebugLogger.getInstance();

    /** Gestionnaire de sécurité pour TLS, HMAC, rate limiting, sanitisation. */
    private final SecurityManager security = new SecurityManager();

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
        this.playerName = playerName;
        this.gameEngine = new GameEngine();
        this.client     = null;
    }

    public P2PManager(String playerName, GGClient client) {
        this.playerName = playerName;
        this.gameEngine = new GameEngine();
        this.client     = client;
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
        this.client     = null;
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
        listeningPort = p2pServerSocket.getLocalPort();
        logger.logEvent("P2PManager : écoute P2P démarrée sur le port " + listeningPort);

        p2pAcceptor = new P2PAcceptor();
        p2pAcceptor.start();
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
            // Évite la connexion symétrique en ne créant une connexion sortante
            // que vers les pairs dont le nom est lexicalement plus grand.
            if (peerName.compareTo(playerName) <= 0) continue;

            Socket existingSocket = peers.get(peerName);
            if (existingSocket != null) {
                if (!existingSocket.isClosed()) {
                    logger.logEvent("P2PManager : connexion existante vers " + peerName + " conservée.");
                    continue;
                }
                closePeerConnection(peerName);
            }

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
                socket = security.wrapTLS(socket);
                peers.put(peerName, socket);
                logger.logEvent("P2PManager : connecté au pair " + peerName + " (" + ip + ":" + port + ")");
                // Envoyer un HELLO pour que le pair puisse nous enregistrer (connexion entrante chez lui)
                String helloMsg = MessageParser.serialize(CommandType.HELLO, playerName);
                socket.getOutputStream().write((security.signMessage(helloMsg) + "\n").getBytes("UTF-8"));
                socket.getOutputStream().flush();
                PeerListener listener = new PeerListener(socket, this, gameEngine, security);
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
        if (!isPlayerTurn(playerName)) {
            logger.logEvent("P2PManager : tentative de guess hors tour ignorée.");
            System.out.println("[P2PManager] Ce n'est pas votre tour. Attendez le tour de " + getCurrentTurnPlayer() + ".");
            return;
        }
        if (currentSecretOwner == null) {
            logger.logEvent("P2PManager : tentative de guess ignorée car aucun secret défini.");
            System.out.println("[P2PManager] Impossible de deviner : aucun secret n'a encore été défini.");
            return;
        }
        if (!gameEngine.canMakeGuess()) {
            logger.logEvent("P2PManager : tentative de guess ignorée car plus de tentatives.");
            System.out.println("[P2PManager] Vous n'avez plus de tentatives. Impossible d'envoyer un nouveau guess.");
            return;
        }
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

        // Consomme la tentative locale du joueur qui envoie la proposition.
        if (!gameEngine.consumeAttempt()) {
            logger.logEvent("P2PManager : impossible de consommer la tentative, partie terminée.");
            System.out.println("[P2PManager] Impossible d'envoyer le guess : partie terminée.");
            return;
        }

        StringBuilder sb = new StringBuilder("GG|GUESS");
        for (Color c : colors) {
            sb.append("|").append(c.name());
        }
        boolean willBeOut = gameEngine.isGameOver();
        if (willBeOut) {
            sb.append("|LAST");
        }
        String msg = sb.toString();
        logger.logOutgoingRaw(msg);

        // Auto-évaluation si ce client est le propriétaire du secret ET joueur (Cas 1 : admin joueur)
        // Dans ce cas on n'envoie pas le GUESS sur le réseau — tout est local.
        if (adminIsPlayer && gameEngine.isSecretOwner()) {
            try {
                Feedback feedback = gameEngine.checkGuess(colors, playerName);
                System.out.println("[GAME] Feedback : " + feedback.getCorrectColors()
                        + " couleur(s) correcte(s), " + feedback.getCorrectPositions() + " position(s) correcte(s)");
                if (!feedback.isWin() && !willBeOut) {
                    System.out.println("[GAME] Tentatives restantes : " + gameEngine.getAttemptsLeft());
                }

                // PAS de broadcast du feedback — l'admin-joueur le voit localement uniquement

                if (feedback.isWin()) {
                    broadcast(MessageParser.serialize(CommandType.WINNER, playerName));
                    broadcast(MessageParser.serialize(CommandType.GAME_OVER, "WIN", playerName));
                    gameEngine.setGameOver(true);
                    System.out.println("[GAME] \u2605\u2605\u2605 VOUS AVEZ GAGN\u00c9 ! \u2605\u2605\u2605");
                    System.out.println("[GAME] Tapez 'newgame' pour lancer une nouvelle manche, ou 'leave <salle>' pour quitter.");
                    notifyServerGameOver("WIN", playerName);
                    return;
                }

                if (willBeOut) {
                    decrementActivePlayers();
                    broadcast(MessageParser.serialize(CommandType.PLAYER_OUT, playerName));
                    removePlayerFromTurnOrder(playerName);
                    System.out.println("[P2PManager] Vous avez épuisé vos tentatives.");
                    if (getTurnOrderSize() == 0) {
                        broadcast(MessageParser.serialize(CommandType.GAME_OVER, "LOSE", "NONE"));
                        gameEngine.setGameOver(true);
                        System.out.println("[GAME] Partie terminée : tous les joueurs ont épuisé leurs tentatives.");
                        System.out.println("[GAME] Tapez 'newgame' pour lancer une nouvelle manche, ou 'leave <salle>' pour quitter.");
                        notifyServerGameOver("LOSE", "NONE");
                        return;
                    }
                    // D'autres joueurs restent — réinitialiser gameOver pour pouvoir évaluer leurs guesses
                    gameEngine.setGameOver(false);
                    // removePlayerFromTurnOrder (Option A) pointe déjà sur le joueur suivant — pas de nextTurn()
                } else {
                    nextTurn();
                }
                String nextPlayer = getCurrentTurnPlayer();
                if (nextPlayer != null) {
                    broadcast(MessageParser.serialize(CommandType.NEXT_TURN, nextPlayer));
                    // Alice ne reçoit pas ses propres broadcasts — l'informer localement
                    if (nextPlayer.equals(playerName)) {
                        System.out.println("[GAME] C'est votre tour de deviner !");
                    } else {
                        System.out.println("[GAME] C'est le tour de " + nextPlayer + " de deviner.");
                    }
                }
            } catch (Exception e) {
                logger.logError("P2PManager : erreur auto-évaluation du guess", e);
            }
            return;
        }

        // Envoie le GUESS uniquement au propriétaire du secret — les autres joueurs ne doivent pas le voir
        if (currentSecretOwner != null) {
            sendToPeer(currentSecretOwner, msg);
        } else {
            logger.logEvent("P2PManager : aucun propriétaire du secret connu, GUESS non envoyé.");
        }

        if (gameEngine.isGameOver()) {
            System.out.println("[GAME] Vous avez épuisé vos tentatives. En attente du feedback...");
        } else if (gameEngine.getAttemptsLeft() > 0) {
            System.out.println("[GAME] Tentatives restantes : " + gameEngine.getAttemptsLeft());
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
        if (!gameEngine.isSecretOwner()) {
            logger.logError("P2PManager : seuls le propriétaire du secret peut envoyer le feedback.");
            return;
        }

        Feedback feedback = new Feedback(correctColors, correctPositions);
        String msg = feedback.toGGString();
        logger.logOutgoingRaw(msg);

        // Diffusion à tous (on ne sait pas qui a posé la question depuis le CLI)
        broadcast(msg);

        // Si victoire, diffuser WINNER et GAME_OVER
        if (feedback.isWin()) {
            String winner = playerName;
            broadcast(MessageParser.serialize(CommandType.WINNER, winner));
            broadcast(MessageParser.serialize(CommandType.GAME_OVER, "WIN", winner));
            gameEngine.setGameOver(true);
        }
    }

    /**
     * Annonce le gagnant (WINNER). Seul le propriétaire du secret peut le déclarer.
     * @param winnerName nom du joueur gagnant.
     */
    public void announceWinner(String winnerName) {
        if (!gameEngine.isSecretOwner()) {
            logger.logError("P2PManager : seuls le propriétaire du secret peut annoncer le gagnant.");
            return;
        }

        String msg = MessageParser.serialize(CommandType.WINNER, winnerName);
        logger.logOutgoingRaw(msg);
        broadcast(msg);
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
            broadcast(MessageParser.serialize(CommandType.GAME_OVER, "WIN", guesserName));
            gameEngine.setGameOver(true);
            notifyServerGameOver("WIN", guesserName);
            logger.logEvent("P2PManager : partie terminée, gagnant = " + guesserName);
        }
    }

    /**
     * Annonce que ce client a choisi son secret (GG|SECRET_SET|nom_joueur).
     * Diffusé à tous les pairs.
     */
    public void announceSecretSet() {
        if (!canSetSecret(playerName)) {
            logger.logError("P2PManager : tentative de SECRET_SET refusée — seul l'admin peut poser le secret pour cette manche.");
            System.out.println("[P2PManager] SECRET_SET refusé : seul l'admin peut définir le secret pour cette manche.");
            return;
        }
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
        lastAnnouncedTurnPlayer = null;
        gameOverReportedToServer = false;
        adminIsPlayer = false;
        currentTurnIndex = 0;
        gameCount++;
        // Recalculer un nouvel ordre mélangé depuis la liste de base
        if (!basePlayersList.isEmpty()) {
            List<String> shuffled = new ArrayList<>(basePlayersList);
            java.util.Collections.shuffle(shuffled,
                    new java.util.Random(computeSeed(basePlayersList) + (long) gameCount * 1_000_003L));
            playersInOrder = shuffled;
            initialPlayersInOrder = new ArrayList<>(shuffled);
        }
        activePlayersCount = playersInOrder.size();
        logger.logEvent("P2PManager : réinitialisé pour nouvelle manche (" + gameCount + "), ordre : " + playersInOrder);
    }

    /**
     * Notifie le serveur de la fin de la partie P2P (GAME_OVER).
     */
    public void notifyServerGameOver(String result, String winner) {
        if (client == null) {
            logger.logEvent("P2PManager : aucun client pour notifier le serveur de la fin de partie.");
            return;
        }
        if (gameOverReportedToServer) {
            return;
        }
        client.sendToServer(MessageParser.serialize(common.CommandType.GAME_OVER, result, winner));
        gameOverReportedToServer = true;
        logger.logEvent("P2PManager : GAME_OVER notifié au serveur (" + result + ", " + winner + ").");
    }

    // -------------------------------------------------------------------------
    // Enregistrement d'un pair (appelé par PeerListener)
    // -------------------------------------------------------------------------

    /**
     * Enregistre le socket d'un pair identifié par son nom.
     * Appelé par PeerListener quand il identifie le pair depuis le premier message.
     */
    public void registerPeer(String peerName, Socket peerSocket) {
        if (peerName == null || peerSocket == null) return;

        Socket existingSocket = peers.get(peerName);
        if (existingSocket != null && !existingSocket.equals(peerSocket)) {
            logger.logEvent("P2PManager : connection existante pour " + peerName + " remplacée.");
            closePeerConnection(peerName);
        }

        peers.put(peerName, peerSocket);
        logger.logEvent("P2PManager : pair enregistré : " + peerName);
    }

    /**
     * Désenregistre un pair suite à une déconnexion ou une fermeture de socket.
     */
    public void unregisterPeer(String peerName) {
        if (peerName == null) return;
        closePeerConnection(peerName);
        removePlayerFromTurnOrder(peerName);
        logger.logEvent("P2PManager : pair désenregistré : " + peerName);
    }

    /**
     * Enregistre un pair entrant identifié par son message HELLO.
     * Appelé par PeerListener lorsqu'il reçoit GG|HELLO|nom.
     */
    public void registerIncomingPeer(String name, Socket socket) {
        if (name == null || name.isBlank()) return;
        peers.putIfAbsent(name, socket);
        logger.logEvent("P2PManager : pair entrant enregistré : " + name);
    }

    /**
     * Retourne le nom du pair correspondant à un socket, si connu.
     */
    public String getPeerNameBySocket(Socket socket) {
        if (socket == null) return null;
        for (Map.Entry<String, Socket> entry : peers.entrySet()) {
            if (entry.getValue().equals(socket)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Met à jour le détenteur du secret courant.
     * Appelé par PeerListener à la réception de SECRET_SET.
     *
     * @param ownerName nom du joueur qui détient le secret
     */
    public void setCurrentSecretOwner(String ownerName) {
        if (currentSecretOwner != null && !gameEngine.isGameOver() && !currentSecretOwner.equals(ownerName)) {
            logger.logEvent("P2PManager : tentative de nouveau SECRET_SET ignorée (" + ownerName + ", déjà " + currentSecretOwner + ").");
            return;
        }
        this.currentSecretOwner = ownerName;
        logger.logEvent("P2PManager : détenteur du secret mis à jour : " + ownerName);
    }

    public void setRoomAdminName(String adminName) {
        this.roomAdminName = adminName;
    }

    public String getRoomAdminName() {
        return roomAdminName;
    }

    public boolean canSetSecret(String playerName) {
        if (!secretRestrictedToAdmin) {
            return true;
        }
        return playerName != null && playerName.equals(roomAdminName);
    }

    /**
     * Calcule un seed déterministe depuis une liste triée de noms.
     * Identique sur tous les clients pour un même ensemble de joueurs.
     */
    private long computeSeed(List<String> sortedPlayers) {
        long seed = 0L;
        for (String p : sortedPlayers) {
            seed = seed * 31L + p.hashCode();
        }
        return seed;
    }

    /**
     * Définit la liste des joueurs pour les tours.
     * L'ordre est mélangé de façon déterministe (même résultat sur tous les clients).
     * @param players liste des noms de joueurs
     */
    public void setPlayersList(List<String> players) {
        // Dédupliquer puis trier pour la base de référence
        List<String> base = new ArrayList<>(new java.util.LinkedHashSet<>(players));
        java.util.Collections.sort(base);
        this.basePlayersList = new ArrayList<>(base);

        // Mélange déterministe : seed basé sur les noms + numéro de manche
        List<String> shuffled = new ArrayList<>(base);
        java.util.Collections.shuffle(shuffled, new java.util.Random(computeSeed(base) + (long) gameCount * 1_000_003L));

        this.playersInOrder = shuffled;
        this.initialPlayersInOrder = new ArrayList<>(shuffled);
        this.currentTurnIndex = 0;
        this.activePlayersCount = playersInOrder.size();
        logger.logEvent("P2PManager : ordre des tours mélangé (manche " + gameCount + ") : " + playersInOrder
                + ", premier joueur : " + getCurrentTurnPlayer());
    }

    /**
     * Annonce le tour actuel uniquement si le secret est déjà défini.
     */
    public void announceCurrentTurnIfSecretDefined() {
        String currentPlayer = getCurrentTurnPlayer();
        if (currentPlayer == null || currentSecretOwner == null) return;
        if (currentPlayer.equals(lastAnnouncedTurnPlayer)) {
            logger.logEvent("P2PManager : annonce de tour déjà envoyée pour " + currentPlayer + ". Duplication évitée.");
            return;
        }
        lastAnnouncedTurnPlayer = currentPlayer;
        broadcast(MessageParser.serialize(CommandType.TURN_ANNOUNCEMENT, currentPlayer));
        logger.logEvent("P2PManager : annonce du tour pour " + currentPlayer);
    }

    /**
     * Retourne le joueur dont c'est le tour.
     */
    public String getCurrentTurnPlayer() {
        if (playersInOrder.isEmpty()) return null;
        return playersInOrder.get(currentTurnIndex);
    }

    /**
     * Vérifie si c'est le tour du joueur donné.
     */
    public boolean isPlayerTurn(String playerName) {
        return playerName != null && playerName.equals(getCurrentTurnPlayer());
    }

    /**
     * Passe au tour suivant.
     */
    public void nextTurn() {
        if (playersInOrder.isEmpty()) return;
        currentTurnIndex = (currentTurnIndex + 1) % playersInOrder.size();
        logger.logEvent("P2PManager : tour passé à " + getCurrentTurnPlayer());
    }

    /**
     * Définit le joueur dont c'est le tour.
     */
    public void setCurrentTurnPlayer(String playerName) {
        if (playersInOrder.isEmpty() || playerName == null) return;
        int index = playersInOrder.indexOf(playerName);
        if (index >= 0) {
            currentTurnIndex = index;
        }
    }

    /**
     * Supprime un joueur de l'ordre des tours.
     * Ajuste l'index courant pour garder un tour valide.
     */
    public void removePlayerFromTurnOrder(String playerName) {
        if (playerName == null || playersInOrder.isEmpty()) return;
        int removedIndex = playersInOrder.indexOf(playerName);
        if (removedIndex < 0) return;

        playersInOrder.remove(removedIndex);
        // Ne pas retirer de initialPlayersInOrder : nécessaire pour restaurer l'ordre complet après NEW_GAME
        if (playerName.equals(lastAnnouncedTurnPlayer)) {
            lastAnnouncedTurnPlayer = null;
        }

        if (playersInOrder.isEmpty()) {
            currentTurnIndex = 0;
            logger.logEvent("P2PManager : ordre des tours vide après suppression de " + playerName);
            return;
        }

        if (removedIndex < currentTurnIndex) {
            currentTurnIndex--;
        } else if (removedIndex == currentTurnIndex) {
            // Après suppression, l'élément à removedIndex est maintenant le joueur suivant.
            // On clampe si on est en bout de liste, sinon l'index est déjà correct.
            if (currentTurnIndex >= playersInOrder.size()) {
                currentTurnIndex = 0;
            }
        }

        logger.logEvent("P2PManager : joueur retiré de l'ordre des tours : " + playerName
                + " | ordre restant : " + playersInOrder
                + " | tour actuel : " + getCurrentTurnPlayer());
    }

    /**
     * Retourne la taille de l'ordre des tours.
     */
    public int getTurnOrderSize() {
        return playersInOrder.size();
    }

    /**
     * Décrémente le nombre de joueurs actifs.
     */
    public void decrementActivePlayers() {
        if (activePlayersCount > 0) {
            activePlayersCount--;
            logger.logEvent("P2PManager : joueurs actifs : " + activePlayersCount);
        }
    }

    /**
     * Retourne le nombre de joueurs actifs.
     */
    public int getActivePlayersCount() {
        return activePlayersCount;
    }

    public boolean isSecretRestrictedToAdmin() {
        return secretRestrictedToAdmin;
    }

    public boolean isAdminIsPlayer() {
        return adminIsPlayer;
    }

    public void setAdminIsPlayer(boolean val) {
        this.adminIsPlayer = val;
        logger.logEvent("P2PManager : adminIsPlayer = " + val);
    }

    /**
     * Génère un secret aléatoire via le GameEngine (Cas 1 : admin joueur).
     */
    public void generateRandomSecret() {
        gameEngine.generateRandomSecret();
        currentSecretOwner = playerName;
        logger.logEvent("P2PManager : secret aléatoire généré, admin est joueur.");
    }

    /**
     * Définit la combinaison secrète localement pour ce client.
     * Le client doit être le donneur de secret.
     */
    public void setSecret(java.util.List<Color> combo) {
        if (combo == null || combo.size() != GameEngine.COMBINATION_SIZE) {
            throw new IllegalArgumentException("La combinaison doit contenir exactement " + GameEngine.COMBINATION_SIZE + " couleurs.");
        }
        if (gameEngine.isGameOver()) {
            throw new IllegalStateException("La partie est terminée, vous ne pouvez pas (re)définir un secret.");
        }
        if (secretRestrictedToAdmin && !playerName.equals(roomAdminName)) {
            throw new IllegalStateException("Seul l'admin peut définir le secret pour cette manche.");
        }
        if (gameEngine.isSecretOwner() && !gameEngine.isGameOver()) {
            throw new IllegalStateException("Secret déjà défini pour cette manche. Attendez NEW_GAME.");
        }

        gameEngine.setSecret(combo);
        currentSecretOwner = playerName;
        logger.logEvent("P2PManager : secret local défini par " + playerName + " (combinaison masquée).");
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
        for (PeerListener listener : peerListeners) {
            listener.interrupt();
        }
        peerListeners.clear();

        if (p2pServerSocket != null && !p2pServerSocket.isClosed()) {
            try { p2pServerSocket.close(); }
            catch (IOException e) {
                logger.logError("P2PManager : erreur fermeture ServerSocket.", e);
            }
        }

        if (p2pAcceptor != null) {
            p2pAcceptor.shutdown();
            p2pAcceptor = null;
        }

        currentSecretOwner   = null;
        roomAdminName        = null;
        adminIsPlayer        = false;
        playersInOrder.clear();
        initialPlayersInOrder.clear();
        currentTurnIndex     = 0;
        activePlayersCount   = 0;
        lastAnnouncedTurnPlayer = null;
        gameOverReportedToServer   = false;
        gameEngine.reset();

        logger.logEvent("P2PManager : toutes les connexions P2P fermées et l'état local réinitialisé.");
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
            socket.getOutputStream().write((security.signMessage(rawMessage) + "\n").getBytes("UTF-8"));
            socket.getOutputStream().flush();
        } catch (IOException e) {
            logger.logError("P2PManager : erreur d'envoi vers " + peerName, e);
        }
    }

    private void closePeerConnection(String peerName) {
        Socket socket = peers.remove(peerName);
        if (socket != null) {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                logger.logError("P2PManager : erreur fermeture ancienne connexion vers " + peerName, e);
            }
        }
        removePlayerFromTurnOrder(peerName);
        peerListeners.removeIf(listener -> {
            if (listener.getPeerName() == null) return false;
            if (listener.getPeerName().equals(peerName)) {
                listener.interrupt();
                return true;
            }
            return false;
        });
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public GameEngine getGameEngine()        { return gameEngine; }
    public String     getPlayerName()        { return playerName; }
    public String     getCurrentSecretOwner(){ return currentSecretOwner; }
    public int        getListeningPort()     { return listeningPort; }

    // -------------------------------------------------------------------------
    // P2P Acceptor Thread
    // -------------------------------------------------------------------------

    /**
     * Thread pour accepter les connexions P2P entrantes (si ce client est l'hôte).
     */
    private class P2PAcceptor extends Thread {
        private volatile boolean running = true;

        public P2PAcceptor() {
            super("P2PAcceptor");
            setDaemon(true);
        }

        @Override
        public void run() {
            logger.logEvent("P2PAcceptor : démarrage de l'écoute des connexions P2P entrantes.");
            while (running && p2pServerSocket != null && !p2pServerSocket.isClosed()) {
                try {
                    Socket socket = p2pServerSocket.accept();
                    socket = security.wrapTLS(socket);
                    logger.logEvent("P2PAcceptor : connexion entrante de " + socket.getInetAddress());
                    PeerListener listener = new PeerListener(socket, P2PManager.this, gameEngine, security);
                    peerListeners.add(listener);
                    listener.start();
                } catch (IOException e) {
                    if (running) {
                        logger.logError("P2PAcceptor : erreur lors de l'acceptation d'une connexion.", e);
                    }
                }
            }
            logger.logEvent("P2PAcceptor : arrêt.");
        }

        public void shutdown() {
            running = false;
            if (p2pServerSocket != null && !p2pServerSocket.isClosed()) {
                try {
                    p2pServerSocket.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
