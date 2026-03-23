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
 *
 * Responsabilités :
 *  - Gérer la liste des joueurs connectés à la salle.
 *  - Créer et enchaîner des GameSession (parties successives).
 *  - Fournir les adresses P2P des joueurs pour GAME_STARTED.
 *  - Conserver l'historique de toutes les sessions jouées.
 *
 * Hypothèses :
 *  - Le créateur de la salle devient automatiquement admin (adminName).
 *  - Si l'admin quitte, le premier joueur restant devient le nouvel admin.
 *  - Une seule session peut être active à la fois (currentSession).
 *  - Les sessions terminées sont conservées dans sessionHistory.
 *  - L'adresse P2P d'un joueur est fournie lors du JOIN_ROOM sous la forme
 *    "nom:ip:port" et stockée dans playerAddresses.
 *  - La classe est thread-safe : les méthodes de mutation sont synchronized.
 *    CopyOnWriteArrayList est utilisé pour players afin de permettre
 *    une itération concurrente sans lock lors des broadcasts.
 */
public class Room {

    // -------------------------------------------------------------------------
    // État
    // -------------------------------------------------------------------------

    /** Nom unique de la salle. */
    private final String name;

    /** Nombre maximum de joueurs autorisés. */
    private final int maxPlayers;

    /** Nombre maximum de tentatives par partie. */
    private final int maxAttempts;

    /**
     * Liste des handlers actifs dans cette salle.
     * CopyOnWriteArrayList : itération safe pendant les broadcasts.
     */
    private final CopyOnWriteArrayList<ClientHandler> players;

    /** Nom du joueur administrateur de la salle. */
    private volatile String adminName;

    /**
     * Table d'adresses P2P : nom_joueur → "ip:port".
     * Remplie lors du JOIN_ROOM, utilisée pour construire GAME_STARTED.
     */
    private final Map<String, String> playerAddresses;

    /** Session de jeu en cours (null si aucune partie active). */
    private volatile GameSession currentSession;

    /** Historique de toutes les sessions passées (terminées). */
    private final List<GameSession> sessionHistory;

    // -------------------------------------------------------------------------
    // Constructeur
    // -------------------------------------------------------------------------

    /**
     * Crée une salle de jeu.
     *
     * @param name        nom unique de la salle
     * @param maxPlayers  nombre maximum de joueurs
     * @param maxAttempts nombre maximum de tentatives par partie
     * @param creator     handler du joueur créateur (devient admin)
     */
    public Room(String name, int maxPlayers, int maxAttempts, ClientHandler creator) {
        this.name            = name;
        this.maxPlayers      = maxPlayers;
        this.maxAttempts     = maxAttempts;
        this.players         = new CopyOnWriteArrayList<>();
        this.playerAddresses = new HashMap<>();
        this.sessionHistory  = new ArrayList<>();
        this.adminName       = creator.getPlayerName();

        // Le créateur rejoint automatiquement
        players.add(creator);

        DebugLogger.getInstance().logEvent(
                "Salle créée : name=" + name +
                        " maxJoueurs=" + maxPlayers +
                        " maxTentatives=" + maxAttempts +
                        " admin=" + adminName
        );
    }

    // -------------------------------------------------------------------------
    // Gestion des joueurs
    // -------------------------------------------------------------------------

    /**
     * Ajoute un joueur à la salle.
     *
     * @param handler  handler du joueur à ajouter
     * @param p2pAddress adresse P2P du joueur au format "ip:port"
     * @return true si l'ajout a réussi, false si la salle est pleine
     *         ou si le joueur est déjà présent
     */
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
     * Retire un joueur de la salle (déconnexion volontaire ou LEAVE_ROOM).
     * Si le joueur était admin et qu'il reste des joueurs, le premier
     * joueur restant est promu admin.
     * Si une session est en cours, elle est marquée ABORTED.
     *
     * @param playerName nom du joueur à retirer
     */
    public synchronized void removePlayer(String playerName) {
        players.removeIf(h -> h.getPlayerName().equals(playerName));
        playerAddresses.remove(playerName);

        // Ré-attribution de l'admin si nécessaire
        if (playerName.equals(adminName) && !players.isEmpty()) {
            adminName = players.get(0).getPlayerName();
            DebugLogger.getInstance().logEvent(
                    "Salle [" + name + "] : nouvel admin → " + adminName
            );
        }

        // Abandon de la session en cours
        if (currentSession != null && !currentSession.isFinished()) {
            currentSession.abort();
            archiveCurrentSession();
        }

        DebugLogger.getInstance().logEvent(
                "Salle [" + name + "] : joueur retiré=" + playerName +
                        " restants=" + players.size()
        );
    }

    /**
     * Expulse un joueur de la salle (KICK_PLAYER).
     * Appelle removePlayer() après vérification de la permission (déléguée à PermissionManager).
     *
     * Hypothèse : la vérification de permission est faite par ClientHandler
     * avant d'appeler kickPlayer().
     *
     * @param playerName nom du joueur à expulser
     * @return le ClientHandler expulsé, ou null s'il n'est pas trouvé
     */
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

    // -------------------------------------------------------------------------
    // Gestion des sessions
    // -------------------------------------------------------------------------

    /**
     * Crée et démarre une nouvelle session de jeu.
     * L'ancienne session (si terminée) est archivée dans sessionHistory.
     *
     * @return la nouvelle GameSession dans l'état WAITING
     * @throws IllegalStateException si une session est déjà en cours (non terminée)
     */
    public synchronized GameSession startNewSession() {
        if (currentSession != null && !currentSession.isFinished()) {
            throw new IllegalStateException(
                    "Salle [" + name + "] : impossible de démarrer une nouvelle session, " +
                            "la session courante est encore en cours."
            );
        }

        // Archiver l'ancienne session si elle existe
        archiveCurrentSession();

        currentSession = new GameSession(name, maxAttempts);

        DebugLogger.getInstance().logEvent(
                "Salle [" + name + "] : nouvelle session démarrée [" +
                        currentSession.getSessionId() + "]" +
                        " (partie #" + (sessionHistory.size() + 1) + ")"
        );
        return currentSession;
    }

    /**
     * Déplace la session courante dans l'historique.
     * Appel interne uniquement.
     */
    private void archiveCurrentSession() {
        if (currentSession != null) {
            sessionHistory.add(currentSession);
            currentSession = null;
        }
    }

    // -------------------------------------------------------------------------
    // Construction de la liste P2P pour GAME_STARTED
    // -------------------------------------------------------------------------

    /**
     * Construit la chaîne de la liste des joueurs avec leurs adresses P2P,
     * au format attendu par le message GG|GAME_STARTED :
     *   "joueur1:ip1:port1,joueur2:ip2:port2,..."
     *
     * Hypothèse : les joueurs sans adresse P2P enregistrée sont ignorés.
     *
     * @return la chaîne CSV des entrées joueur:ip:port
     */
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

    /**
     * Construit la liste des noms de joueurs pour JOINED_ROOM :
     *   "joueur1,joueur2,..."
     *
     * @return la chaîne CSV des noms
     */
    public synchronized String buildPlayerList() {
        StringBuilder sb = new StringBuilder();
        for (ClientHandler handler : players) {
            if (sb.length() > 0) sb.append(",");
            sb.append(handler.getPlayerName());
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Utilitaires de recherche
    // -------------------------------------------------------------------------

    /**
     * Vérifie si un joueur (par nom) est dans la salle.
     *
     * @param playerName nom à chercher
     * @return true si présent
     */
    public boolean containsPlayer(String playerName) {
        for (ClientHandler h : players) {
            if (h.getPlayerName().equals(playerName)) return true;
        }
        return false;
    }

    /**
     * Recherche un ClientHandler par nom de joueur.
     *
     * @param playerName nom du joueur
     * @return le ClientHandler correspondant, ou null
     */
    public ClientHandler findPlayer(String playerName) {
        for (ClientHandler h : players) {
            if (h.getPlayerName().equals(playerName)) return h;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Accesseurs
    // -------------------------------------------------------------------------

    public String getName()                          { return name; }
    public int getMaxPlayers()                       { return maxPlayers; }
    public int getMaxAttempts()                      { return maxAttempts; }
    public String getAdminName()                     { return adminName; }
    public int getPlayerCount()                      { return players.size(); }
    public boolean isFull()                          { return players.size() >= maxPlayers; }
    public boolean isEmpty()                         { return players.isEmpty(); }

    /**
     * @return vue non-modifiable des handlers présents dans la salle
     */
    public List<ClientHandler> getPlayers() {
        return Collections.unmodifiableList(new ArrayList<>(players));
    }

    /**
     * @return la session de jeu courante, ou null si aucune partie active
     */
    public GameSession getCurrentSession()           { return currentSession; }

    /**
     * @return vue non-modifiable de l'historique des sessions
     */
    public List<GameSession> getSessionHistory() {
        return Collections.unmodifiableList(sessionHistory);
    }

    /**
     * @return la table nom → "ip:port" des adresses P2P enregistrées
     */
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