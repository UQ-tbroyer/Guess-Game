package server;

import common.CommandType;
import common.DebugLogger;
import common.Message;
import common.MessageParser;
import common.ParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * ClientHandler — thread dédié à un client connecté au serveur GG.
 *
 * Responsabilités :
 *  - Lire en continu les messages GG depuis le socket client.
 *  - Parser et dispatcher chaque commande vers le handler approprié.
 *  - Envoyer les réponses GG au client.
 *  - Gérer proprement la déconnexion (IOException → nettoyage de la salle).
 *
 * Hypothèses :
 *  - Un ClientHandler est créé par GGServer pour chaque connexion TCP entrante.
 *  - La première commande attendue est GG|CONNECT|nom_joueur.
 *    Toute autre commande avant CONNECT reçoit GG|ERROR.
 *  - Si le client se déconnecte en cours de partie, la partie est annulée
 *    et tous les joueurs de la salle reçoivent une notification.
 *  - Le port P2P du client est passé dans JOIN_ROOM (4e champ optionnel).
 *    Format étendu : GG|JOIN_ROOM|salle|portP2P
 *    Si absent, le port P2P reste 0 (non déclaré).
 */
public class ClientHandler implements Runnable {

    // -------------------------------------------------------------------------
    // Attributs
    // -------------------------------------------------------------------------

    private final Socket           socket;
    private final GGServer         server;
    private final PermissionManager permissions;

    private final BufferedReader   in;
    private final PrintWriter      out;

    private final DebugLogger      logger = DebugLogger.getInstance();

    /** Nom du joueur — null jusqu'à réception de GG|CONNECT. */
    private String playerName = null;

    /** Salle courante du joueur — null si hors salle. */
    private String currentRoom = null;

    /** Adresse IP du client (pour le payload GAME_STARTED). */
    private final String clientIp;

    // -------------------------------------------------------------------------
    // Constructeur
    // -------------------------------------------------------------------------

    /**
     * @param socket     socket TCP du client
     * @param server     référence au serveur (état partagé)
     * @throws IOException si les flux I/O ne peuvent pas être ouverts
     */
    public ClientHandler(Socket socket, GGServer server) throws IOException {
        this.socket      = socket;
        this.server      = server;
        this.permissions = server.getPermissionManager();
        this.clientIp    = socket.getInetAddress().getHostAddress();
        this.in  = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8"));
        this.out = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
    }

    // -------------------------------------------------------------------------
    // Boucle principale
    // -------------------------------------------------------------------------

    @Override
    public void run() {
        logger.logEvent("ClientHandler démarré pour " + clientIp);
        try {
            String rawLine;
            while ((rawLine = in.readLine()) != null) {
                handleRawLine(rawLine.trim());
            }
        } catch (IOException e) {
            if (playerName != null) {
                logger.logError("ClientHandler : connexion perdue avec " + playerName, e);
            }
        } finally {
            handleDisconnect();
        }
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    private void handleRawLine(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) return;

        Message msg;
        try {
            msg = MessageParser.parse(rawLine);
        } catch (ParseException e) {
            logger.logError("ClientHandler : message invalide ignoré : '" + rawLine + "'", e);
            sendError("Message mal formé : " + e.getMessage());
            return;
        }

        logger.logIncoming(msg);

        // Avant CONNECT, seule la commande CONNECT est acceptée
        if (playerName == null && msg.getCommand() != CommandType.CONNECT) {
            sendError("Vous devez d'abord envoyer GG|CONNECT|nom_joueur.");
            return;
        }

        switch (msg.getCommand()) {
            case CONNECT       -> onConnect(msg);
            case CREATE_ROOM   -> onCreateRoom(msg);
            case LIST_ROOMS    -> onListRooms(msg);
            case JOIN_ROOM     -> onJoinRoom(msg);
            case LEAVE_ROOM    -> onLeaveRoom(msg);
            case KICK_PLAYER   -> onKickPlayer(msg);
            case START_GAME    -> onStartGame(msg);
            case PLAY_SERVER   -> onPlayServer(msg);
            case GUESS         -> onGuess(msg);
            default            -> sendError("Commande non supportée côté serveur : " + msg.getCommand());
        }
    }

    // -------------------------------------------------------------------------
    // Handlers de commandes
    // -------------------------------------------------------------------------

    /**
     * GG|CONNECT|nom_joueur
     * Enregistre le joueur sur le serveur.
     * Réponse : GG|CONNECTED|nom_joueur  ou  GG|ERROR|raison
     */
    private void onConnect(Message msg) {
        try {
            msg.requireFields(1);
        } catch (ParseException e) {
            sendError("CONNECT requiert un nom de joueur.");
            return;
        }

        String name = msg.getField(0).trim();

        if (name.isEmpty()) {
            sendError("Le nom de joueur ne peut pas être vide.");
            return;
        }

        if (permissions.isBanned(name)) {
            sendError("Vous êtes banni de ce serveur.");
            return;
        }

        if (!server.registerClient(name, this)) {
            sendError("Le nom '" + name + "' est déjà pris. Choisissez un autre nom.");
            return;
        }

        this.playerName = name;
        send(MessageParser.serialize(CommandType.CONNECTED, playerName));
        logger.logEvent("Joueur connecté : " + playerName + " depuis " + clientIp);
    }

    /**
     * GG|CREATE_ROOM|nom_salle|max_joueurs|max_tentatives
     * Crée une salle dont ce joueur est l'admin.
     * Réponse : GG|ROOM_CREATED|nom_salle  ou  GG|ERROR|raison
     */
    private void onCreateRoom(Message msg) {
    try {
        msg.requireFields(3); // minimum 3, mais peut avoir 4
    } catch (ParseException e) {
        sendError("CREATE_ROOM requiert : nom_salle|max_joueurs|max_tentatives");
        return;
    }

    String roomName = msg.getField(0).trim();
    int maxPlayers, maxAttempts;
    int p2pPort = 0;

    try {
        maxPlayers  = Integer.parseInt(msg.getField(1));
        maxAttempts = Integer.parseInt(msg.getField(2));
        if (msg.getFieldCount() >= 4) {
            p2pPort = Integer.parseInt(msg.getField(3));
        }
    } catch (NumberFormatException e) {
        sendError("max_joueurs et max_tentatives doivent être des entiers.");
        return;
    }

        if (maxPlayers < 2) {
            sendError("Une salle doit accueillir au moins 2 joueurs.");
            return;
        }
        if (maxAttempts < 1) {
            sendError("Le nombre de tentatives doit être positif.");
            return;
        }

        Room room = server.createRoom(roomName, maxPlayers, maxAttempts, playerName);
        if (room == null) {
            sendError("Une salle nommée '" + roomName + "' existe déjà.");
            return;
        }

        // L'admin rejoint automatiquement sa salle
        //room.addPlayer(playerName, clientIp);
        String adminP2p = clientIp + ":" + p2pPort;
        room.addPlayer(this, adminP2p);
        currentRoom = roomName;

        send(MessageParser.serialize(CommandType.ROOM_CREATED, roomName));
        logger.logEvent(playerName + " a créé la salle : " + roomName);
    }

    /**
     * GG|LIST_ROOMS
     * Retourne la liste des salles disponibles.
     * Réponse : GG|ROOM_LIST|salle1,salle2,...
     */
    private void onListRooms(Message msg) {
        String list = server.getRoomListAsString();
        if (list.isEmpty()) list = "(aucune salle disponible)";
        send(MessageParser.serialize(CommandType.ROOM_LIST, list));
    }

    /**
     * GG|JOIN_ROOM|nom_salle[|portP2P]
     * Rejoint une salle existante.
     * Le 4e champ optionnel est le port P2P déclaré par le client.
     * Réponse : GG|JOINED_ROOM|nom_salle|joueur1,joueur2,...  ou  GG|ERROR|raison
     */
    private void onJoinRoom(Message msg) {
        try {
            msg.requireFields(1);
        } catch (ParseException e) {
            sendError("JOIN_ROOM requiert un nom de salle.");
            return;
        }

        String roomName = msg.getField(0).trim();
        Room room = server.getRoom(roomName);

        if (room == null) {
            sendError("La salle '" + roomName + "' n'existe pas.");
            return;
        }

        if (!permissions.canJoin(room, playerName)) {
            if (permissions.isBanned(playerName)) {
                sendError("Vous êtes banni de ce serveur.");
            } else if (room.isFull()) {
                sendError("La salle '" + roomName + "' est pleine.");
            } else if (room.isGameInProgress()) {
                sendError("Une partie est déjà en cours dans '" + roomName + "'.");
            } else if (room.hasPlayer(playerName)) {
                sendError("Vous êtes déjà dans la salle '" + roomName + "'.");
            } else {
                sendError("Impossible de rejoindre la salle '" + roomName + "'.");
            }
            return;
        }

        // Port P2P optionnel (4e champ)
        int p2pPort = 0;
        if (msg.getFieldCount() >= 2) {
            try {
                p2pPort = Integer.parseInt(msg.getField(1));
            } catch (NumberFormatException ignored) {}
        }

        //room.addPlayer(playerName, clientIp, p2pPort);
        //currentRoom = roomName;

        String p2pAddress = clientIp + ":" + p2pPort;
        if (!room.addPlayer(this, p2pAddress)) {
            sendError("Impossible d'ajouter le joueur à la salle (conflit interne).");
            return;
        }
        currentRoom = roomName;

        // Notifier les autres joueurs de la salle
        String joinedMsg = MessageParser.serialize(CommandType.JOINED_ROOM,
                roomName, room.getPlayersAsString());
        broadcastToRoom(room, joinedMsg);

        logger.logEvent(playerName + " a rejoint la salle : " + roomName);
    }

    /**
     * GG|LEAVE_ROOM|nom_salle
     * Quitte la salle. Si la salle devient vide, elle est supprimée.
     * Réponse : GG|LEFT_ROOM|nom_salle
     */
    private void onLeaveRoom(Message msg) {
        try {
            msg.requireFields(1);
        } catch (ParseException e) {
            sendError("LEAVE_ROOM requiert un nom de salle.");
            return;
        }

        String roomName = msg.getField(0).trim();
        leaveRoom(roomName);
    }

    /**
     * GG|KICK_PLAYER|nom_salle|nom_joueur
     * Expulse un joueur (admin uniquement).
     * Réponse : GG|PLAYER_KICKED|nom_joueur (à tous) ou GG|ERROR|raison
     */
    private void onKickPlayer(Message msg) {
        try {
            msg.requireFields(2);
        } catch (ParseException e) {
            sendError("KICK_PLAYER requiert : nom_salle|nom_joueur");
            return;
        }

        String roomName  = msg.getField(0).trim();
        String targetName = msg.getField(1).trim();
        Room room = server.getRoom(roomName);

        if (room == null) {
            sendError("La salle '" + roomName + "' n'existe pas.");
            return;
        }

        if (!permissions.canKick(room, playerName, targetName)) {
            if (!permissions.isAdmin(room, playerName)) {
                sendError("Seul l'admin peut expulser un joueur.");
            } else {
                sendError("Impossible d'expulser '" + targetName + "'.");
            }
            return;
        }

        // Notifier tout le monde AVANT de retirer le joueur
        String kickedMsg = MessageParser.serialize(CommandType.PLAYER_KICKED, targetName);
        broadcastToRoom(room, kickedMsg);

        // Retirer le joueur de la salle
        room.removePlayer(targetName);

        // Notifier le joueur expulsé (LEFT_ROOM pour cohérence côté client)
        ClientHandler targetHandler = server.getClient(targetName);
        if (targetHandler != null) {
            targetHandler.setCurrentRoom(null);
            targetHandler.send(MessageParser.serialize(CommandType.LEFT_ROOM, roomName));
        }

        logger.logEvent(playerName + " a expulsé " + targetName + " de " + roomName);

        // Nettoyer si salle vide
        if (room.isEmpty()) {
            server.removeRoom(roomName);
        }
    }

    /**
     * GG|START_GAME|nom_salle
     * Démarre la partie (admin uniquement, min. 2 joueurs).
     * Réponse : GG|GAME_STARTED|nom_salle|joueur1:ip1:port1,... (à tous)
     * ou GG|ERROR|raison
     */
    private void onStartGame(Message msg) {
    try {
        msg.requireFields(1);
    } catch (ParseException e) {
        sendError("START_GAME requiert un nom de salle.");
        return;
    }

    String roomName = msg.getField(0).trim();
    Room room = server.getRoom(roomName);

    if (room == null) {
        sendError("La salle '" + roomName + "' n'existe pas.");
        return;
    }

    if (!permissions.canStartGame(room, playerName)) {
        if (!permissions.isAdmin(room, playerName)) {
            sendError("Seul l'admin peut démarrer la partie.");
        } else if (room.isGameInProgress()) {
            sendError("Une partie est déjà en cours.");
        } else if (room.getPlayerCount() < 2) {
            sendError("Il faut au moins 2 joueurs pour démarrer.");
        } else {
            sendError("Impossible de démarrer la partie.");
        }
        return;
    }

    room.setGameInProgress(true);

    // Construction du payload GAME_STARTED
    String payload = room.buildP2PList();
    
    // Log pour déboguer
    logger.logEvent("GAME_STARTED payload: " + payload);
    
    // GG|GAME_STARTED|nom_salle|joueur1:ip1:port1,joueur2:ip2:port2,...
    String gameStartedMsg = "GG|GAME_STARTED|" + roomName + "|" + payload;
    
    // Diffuser à tous les joueurs de la salle
    for (String pName : room.getPlayerNames()) {
        ClientHandler handler = server.getClient(pName);
        if (handler != null) {
            handler.send(gameStartedMsg);
            logger.logEvent("GAME_STARTED envoyé à " + pName + ": " + gameStartedMsg);
        }
    }

    logger.logEvent("Partie démarrée dans la salle : " + roomName
            + " | Joueurs : " + room.getPlayersAsString());
}

    /**
     * GG|PLAY_SERVER|max_tentatives
     * Lance une partie solo contre le serveur.
     * Réponse : GG|SERVER_GAME_STARTED|max_tentatives
     *
     * Hypothèse : la logique de GameSession (secret serveur, feedback)
     * est gérée par Thomas dans GameSession. Ici on se contente
     * de confirmer le démarrage.
     */
    private void onPlayServer(Message msg) {
        try {
            msg.requireFields(1);
        } catch (ParseException e) {
            sendError("PLAY_SERVER requiert un nombre de tentatives.");
            return;
        }

        int maxAttempts;
        try {
            maxAttempts = Integer.parseInt(msg.getField(0));
        } catch (NumberFormatException e) {
            sendError("max_tentatives doit être un entier.");
            return;
        }

        if (maxAttempts < 1) {
            sendError("Le nombre de tentatives doit être positif.");
            return;
        }

        // TODO (Thomas) : créer une GameSession côté serveur pour cette partie solo
        //GameSession session = new GameSession(playerName, maxAttempts);
        //session.start();
        //------------------------

        send(MessageParser.serialize(CommandType.SERVER_GAME_STARTED,
                String.valueOf(maxAttempts)));
        logger.logEvent(playerName + " démarre une partie solo (" + maxAttempts + " tentatives).");
    }

    /**
     * GG|GUESS|c1|c2|c3|c4
     * Proposition dans le cadre d'une partie solo contre le serveur.
     * En mode P2P, les GUESS transitent directement entre clients — ne passent pas ici.
     *
     * Hypothèse : si le message arrive ici, c'est forcément une partie solo (PLAY_SERVER).
     * La logique de feedback est déléguée à GameSession (Thomas).
     */
    private void onGuess(Message msg) {
        // TODO (Thomas) : récupérer la GameSession de ce joueur et calculer le feedback
        //GameSession session = activeServerSessions.get(playerName);
        //if (session == null) { sendError("Aucune partie en cours."); return; }
        //Feedback fb = session.checkGuess(msg);
        //send(fb.toGGString());
        //--------------------------------

        // Placeholder : on répond avec un feedback fictif pour que le client ne bloque pas
        logger.logEvent("GUESS reçu de " + playerName + " (mode solo — délégué à GameSession).");
        send("GG|FEEDBACK|0|0"); // Remplacé quand GameSession est intégrée
    }

    // -------------------------------------------------------------------------
    // Déconnexion
    // -------------------------------------------------------------------------

    /**
     * Appelé dans le bloc finally de run() — nettoie l'état serveur.
     *
     * Hypothèse (décision #6 du README) :
     * Si le joueur était dans une salle en cours de partie,
     * la partie est annulée et tous les joueurs en sont notifiés.
     */
    private void handleDisconnect() {
        logger.logEvent("Déconnexion de : " + (playerName != null ? playerName : clientIp));

        if (playerName != null) {
            // Quitter la salle proprement si on y était
            if (currentRoom != null) {
                Room room = server.getRoom(currentRoom);
                if (room != null) {
                    if (room.isGameInProgress()) {
                        // Annuler la partie — notifier les autres joueurs
                        room.setGameInProgress(false);
                        broadcastToRoom(room,
                                MessageParser.serialize(CommandType.ERROR,
                                        playerName + " s'est déconnecté. Partie annulée."));
                    }
                    room.removePlayer(playerName);
                    if (room.isEmpty()) server.removeRoom(currentRoom);
                }
            }
            server.unregisterClient(playerName);
        }

        closeSilently();
    }

    /**
     * Quitte proprement une salle (sans déconnexion).
     */
    private void leaveRoom(String roomName) {
        Room room = server.getRoom(roomName);
        if (room == null || !room.hasPlayer(playerName)) {
            sendError("Vous n'êtes pas dans la salle '" + roomName + "'.");
            return;
        }

        room.removePlayer(playerName);
        currentRoom = null;

        send(MessageParser.serialize(CommandType.LEFT_ROOM, roomName));

        // Notifier les autres
        if (!room.isEmpty()) {
            broadcastToRoom(room,
                    MessageParser.serialize(CommandType.ROOM_LIST, room.getPlayersAsString()));
        } else {
            server.removeRoom(roomName);
        }

        logger.logEvent(playerName + " a quitté la salle : " + roomName);
    }

    // -------------------------------------------------------------------------
    // Envoi de messages
    // -------------------------------------------------------------------------

    /**
     * Envoie un message GG sérialisé à CE client.
     *
     * @param rawMessage message GG complet (ex: "GG|CONNECTED|Alice")
     */
    public synchronized void send(String rawMessage) {
        logger.logOutgoingRaw(rawMessage);
        out.println(rawMessage);
    }

    /**
     * Envoie un message d'erreur générique au client.
     *
     * @param reason description de l'erreur
     */
    private void sendError(String reason) {
        send(MessageParser.serialize(CommandType.ERROR, reason));
    }

    /**
     * Diffuse un message à tous les joueurs d'une salle.
     *
     * @param room       la salle destinataire
     * @param rawMessage le message à diffuser
     */
    private void broadcastToRoom(Room room, String rawMessage) {
        for (String pName : room.getPlayerNames()) {
            ClientHandler handler = server.getClient(pName);
            if (handler != null) {
                handler.send(rawMessage);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    private void closeSilently() {
        try { if (!socket.isClosed()) socket.close(); }
        catch (IOException ignored) {}
    }

    // -------------------------------------------------------------------------
    // Getters / Setters (utilisés par GGServer et les autres handlers)
    // -------------------------------------------------------------------------

    public String getPlayerName() { return playerName; }
    public String getCurrentRoom(){ return currentRoom; }
    public String getClientIp()   { return clientIp;   }

    /** Appelé par onKickPlayer pour mettre à jour l'état du joueur expulsé. */
    public void setCurrentRoom(String room) { this.currentRoom = room; }
}
