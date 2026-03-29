package server;

import common.DebugLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Représente une salle de jeu sur le serveur.
 */
public class Room {

    private final String name;
    private final int maxPlayers;
    private final int maxAttempts;
    private final CopyOnWriteArrayList<ClientHandler> players;
    private volatile String adminName;
    private final Map<String, String> playerAddresses;
    private volatile GameSession currentSession;
    private final List<GameSession> sessionHistory;

    public Room(String name, int maxPlayers, int maxAttempts, ClientHandler creator) {
        this.name            = name;
        this.maxPlayers      = maxPlayers;
        this.maxAttempts     = maxAttempts;
        this.players         = new CopyOnWriteArrayList<>();
        this.playerAddresses = new HashMap<>();
        this.sessionHistory  = new ArrayList<>();
        this.adminName       = creator.getPlayerName();

        players.add(creator);

        DebugLogger.getInstance().logEvent(
                "Salle créée : name=" + name +
                        " maxJoueurs=" + maxPlayers +
                        " maxTentatives=" + maxAttempts +
                        " admin=" + adminName
        );
    }

    public synchronized boolean addPlayer(ClientHandler handler, String p2pAddress) {
        String playerName = handler.getPlayerName();

        if (players.size() >= maxPlayers) {
            DebugLogger.getInstance().logEvent(
                    "Salle [" + name + "] : refus de " + playerName + " — salle pleine."
            );
            return false;
        }
        if (containsPlayer(playerName)) {
            DebugLogger.getInstance().logEvent(
                    "Salle [" + name + "] : refus de " + playerName + " — déjà présent."
            );
            return false;
        }

        players.add(handler);
        if (p2pAddress != null && !p2pAddress.isBlank()) {
            playerAddresses.put(playerName, p2pAddress);
        }

        DebugLogger.getInstance().logEvent(
                "Salle [" + name + "] : joueur ajouté=" + playerName +
                        " p2p=" + p2pAddress +
                        " total=" + players.size() + "/" + maxPlayers
        );
        return true;
    }

    /**
     * Ajoute un joueur par nom (surcharge pour compatibilité avec ClientHandler).
     */
    public synchronized boolean addPlayer(String playerName, String p2pAddress) {
        ClientHandler handler = findPlayer(playerName);
        if (handler == null) {
            DebugLogger.getInstance().logEvent(
                "Salle [" + name + "] : impossible d'ajouter " + playerName + " - handler introuvable."
            );
            return false;
        }
        return addPlayer(handler, p2pAddress);
    }

    /**
     * Ajoute un joueur avec IP et port séparés.
     */
    public synchronized boolean addPlayer(String playerName, String ip, int port) {
        return addPlayer(playerName, ip + ":" + port);
    }

    public synchronized void removePlayer(String playerName) {
        players.removeIf(h -> h.getPlayerName().equals(playerName));
        playerAddresses.remove(playerName);

        if (playerName.equals(adminName) && !players.isEmpty()) {
            adminName = players.get(0).getPlayerName();
            DebugLogger.getInstance().logEvent(
                    "Salle [" + name + "] : nouvel admin → " + adminName
            );
        }

        if (currentSession != null && !currentSession.isFinished()) {
            currentSession.abort();
            archiveCurrentSession();
        }

        DebugLogger.getInstance().logEvent(
                "Salle [" + name + "] : joueur retiré=" + playerName +
                        " restants=" + players.size()
        );
    }

    public synchronized ClientHandler kickPlayer(String playerName) {
        ClientHandler target = findPlayer(playerName);
        if (target == null) {
            DebugLogger.getInstance().logEvent(
                    "Salle [" + name + "] : kick impossible — " + playerName + " introuvable."
            );
            return null;
        }
        removePlayer(playerName);

        DebugLogger.getInstance().logEvent(
                "Salle [" + name + "] : joueur expulsé=" + playerName
        );
        return target;
    }

    public synchronized GameSession startNewSession() {
        if (currentSession != null && !currentSession.isFinished()) {
            throw new IllegalStateException(
                    "Salle [" + name + "] : impossible de démarrer une nouvelle session, " +
                            "la session courante est encore en cours."
            );
        }

        archiveCurrentSession();

        currentSession = new GameSession(name, maxAttempts);

        DebugLogger.getInstance().logEvent(
                "Salle [" + name + "] : nouvelle session démarrée [" +
                        currentSession.getSessionId() + "]" +
                        " (partie #" + (sessionHistory.size() + 1) + ")"
        );
        return currentSession;
    }

    private void archiveCurrentSession() {
        if (currentSession != null) {
            sessionHistory.add(currentSession);
            currentSession = null;
        }
    }

    /**
     * Indique si une partie est en cours.
     */
    public boolean isGameInProgress() {
        return currentSession != null && !currentSession.isFinished();
    }

    /**
     * Définit l'état de la partie.
     */
    public synchronized void setGameInProgress(boolean inProgress) {
        if (inProgress && currentSession == null) {
            startNewSession();
        } else if (!inProgress && currentSession != null) {
            currentSession.abort();
            archiveCurrentSession();
        }
    }

    public synchronized String buildP2PList() {
        StringBuilder sb = new StringBuilder();
        for (ClientHandler handler : players) {
            String pName = handler.getPlayerName();
            String addr  = playerAddresses.get(pName);
            if (addr != null && !addr.isBlank()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(pName).append(":").append(addr);
            }
        }
        return sb.toString();
    }

    public synchronized String buildPlayerList() {
        StringBuilder sb = new StringBuilder();
        for (ClientHandler handler : players) {
            if (sb.length() > 0) sb.append(",");
            sb.append(handler.getPlayerName());
        }
        return sb.toString();
    }

    public boolean containsPlayer(String playerName) {
        for (ClientHandler h : players) {
            if (h.getPlayerName().equals(playerName)) return true;
        }
        return false;
    }

    /**
     * Vérifie si un joueur est dans la salle (par nom) - alias pour containsPlayer.
     */
    public boolean hasPlayer(String playerName) {
        return containsPlayer(playerName);
    }

    /**
     * Retourne la liste des noms des joueurs (pour JOINED_ROOM).
     */
    public synchronized String getPlayersAsString() {
        StringBuilder sb = new StringBuilder();
        for (ClientHandler handler : players) {
            if (sb.length() > 0) sb.append(",");
            sb.append(handler.getPlayerName());
        }
        return sb.toString();
    }

    /**
     * Retourne la payload pour GAME_STARTED (format "nom:ip:port,...").
     */
    public synchronized String getGameStartedPayload() {
        return buildP2PList();
    }

    /**
     * Retourne la liste des noms des joueurs (pour broadcast).
     */
    public synchronized List<String> getPlayerNames() {
        List<String> names = new ArrayList<>();
        for (ClientHandler handler : players) {
            names.add(handler.getPlayerName());
        }
        return names;
    }

    /**
     * Retourne la liste des noms des joueurs (compatibilité avec ClientHandler).
     */
    public synchronized List<String> getPlayers() {
        return getPlayerNames();
    }

    public ClientHandler findPlayer(String playerName) {
        for (ClientHandler h : players) {
            if (h.getPlayerName().equals(playerName)) return h;
        }
        return null;
    }

    public String getName()                          { return name; }
    public int getMaxPlayers()                       { return maxPlayers; }
    public int getMaxAttempts()                      { return maxAttempts; }
    public String getAdminName()                     { return adminName; }
    public int getPlayerCount()                      { return players.size(); }
    public boolean isFull()                          { return players.size() >= maxPlayers; }
    public boolean isEmpty()                         { return players.isEmpty(); }

    public List<ClientHandler> getPlayersList() {
        return Collections.unmodifiableList(new ArrayList<>(players));
    }

    public GameSession getCurrentSession()           { return currentSession; }

    public List<GameSession> getSessionHistory() {
        return Collections.unmodifiableList(sessionHistory);
    }

    public synchronized Map<String, String> getPlayerAddresses() {
        return Collections.unmodifiableMap(new HashMap<>(playerAddresses));
    }

    @Override
    public String toString() {
        return "Room{name='" + name + "'" +
                ", joueurs=" + players.size() + "/" + maxPlayers +
                ", admin=" + adminName +
                ", sessions=" + (sessionHistory.size() + (currentSession != null ? 1 : 0)) +
                "}";
    }
}