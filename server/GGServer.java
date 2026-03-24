package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * GGServer — Point d'entrée du serveur Guess Game.
 *
 * Responsabilités :
 *  - Ouvrir un ServerSocket sur le port configuré.
 *  - Accepter les connexions entrantes via un ExecutorService (pool de threads).
 *  - Instancier un ClientHandler par client connecté.
 *  - Maintenir les maps partagées : joueurs connectés et salles existantes.
 *  - Gérer l'arrêt propre du serveur (shutdown hook).
 *
 * Hypothèses :
 *  - Un seul PermissionManager est partagé entre tous les ClientHandler.
 *  - La taille du pool de threads est fixée à 50 (suffisant pour la démo).
 *  - Les ConcurrentHashMap garantissent la thread-safety sans synchronized global.
 */
public class GGServer {

    // -------------------------------------------------------------------------
    // Constantes
    // -------------------------------------------------------------------------

    public static final int DEFAULT_PORT    = 5000;
    private static final int THREAD_POOL_SIZE = 50;

    // -------------------------------------------------------------------------
    // État partagé (accès concurrent)
    // -------------------------------------------------------------------------

    /** Map nom_joueur → ClientHandler. */
    private final ConcurrentHashMap<String, ClientHandler> connectedClients =
            new ConcurrentHashMap<>();

    /** Map nom_salle → Room. */
    private final ConcurrentHashMap<String, Room> rooms =
            new ConcurrentHashMap<>();

    /** Gestionnaire de permissions partagé (singleton par serveur). */
    private final PermissionManager permissionManager = new PermissionManager();

    // -------------------------------------------------------------------------
    // Infrastructure réseau
    // -------------------------------------------------------------------------

    private final int port;
    private ServerSocket serverSocket;
    private final ExecutorService threadPool;
    private volatile boolean running = false;

    // -------------------------------------------------------------------------
    // Constructeur
    // -------------------------------------------------------------------------

    public GGServer(int port) {
        this.port       = port;
        this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    // -------------------------------------------------------------------------
    // Démarrage
    // -------------------------------------------------------------------------

    /**
     * Ouvre le ServerSocket et démarre la boucle d'acceptation.
     * Bloque jusqu'à l'arrêt du serveur.
     */
    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            System.out.println("[GGServer] Serveur démarré sur le port " + port);
            System.out.println("[GGServer] En attente de connexions...");
            registerShutdownHook();
            acceptLoop();
        } catch (IOException e) {
            System.err.println("[GGServer] Impossible d'ouvrir le port " + port
                    + " : " + e.getMessage());
        }
    }

    /** Boucle bloquante : accepte les connexions et soumet chaque ClientHandler au pool. */
    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[GGServer] Nouvelle connexion : "
                        + clientSocket.getInetAddress().getHostAddress()
                        + ":" + clientSocket.getPort());

                ClientHandler handler = new ClientHandler(clientSocket, this);
                threadPool.submit(handler);

            } catch (IOException e) {
                if (running) {
                    System.err.println("[GGServer] Erreur d'acceptation : " + e.getMessage());
                }
                // Si !running → arrêt normal, on sort
            }
        }
    }

    // -------------------------------------------------------------------------
    // Arrêt propre
    // -------------------------------------------------------------------------

    public void shutdown() {
        System.out.println("[GGServer] Arrêt en cours...");
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("[GGServer] Erreur fermeture socket : " + e.getMessage());
        }
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("[GGServer] Serveur arrêté.");
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[GGServer] Signal d'arrêt reçu.");
            shutdown();
        }));
    }

    // -------------------------------------------------------------------------
    // Gestion des clients
    // -------------------------------------------------------------------------

    /** Enregistre un client. @return false si le nom est déjà pris. */
    public boolean registerClient(String playerName, ClientHandler handler) {
        return connectedClients.putIfAbsent(playerName, handler) == null;
    }

    public void unregisterClient(String playerName) {
        connectedClients.remove(playerName);
        System.out.println("[GGServer] Client déconnecté : " + playerName);
    }

    public ClientHandler getClient(String playerName) {
        return connectedClients.get(playerName);
    }

    public boolean isNameTaken(String playerName) {
        return connectedClients.containsKey(playerName);
    }

    // -------------------------------------------------------------------------
    // Gestion des salles
    // -------------------------------------------------------------------------

    /** Crée une salle. @return null si le nom est déjà pris. */
    public Room createRoom(String roomName, int maxPlayers, int maxAttempts, String adminName) {
        Room newRoom  = new Room(roomName, maxPlayers, maxAttempts, adminName);
        Room existing = rooms.putIfAbsent(roomName, newRoom);
        return (existing == null) ? newRoom : null;
    }

    public Room getRoom(String roomName) {
        return rooms.get(roomName);
    }

    public void removeRoom(String roomName) {
        rooms.remove(roomName);
        System.out.println("[GGServer] Salle supprimée : " + roomName);
    }

    /** Liste des salles séparées par des virgules — pour GG|ROOM_LIST. */
    public String getRoomListAsString() {
        if (rooms.isEmpty()) return "";
        return String.join(",", rooms.keySet());
    }

    // -------------------------------------------------------------------------
    // Accesseurs
    // -------------------------------------------------------------------------

    public PermissionManager getPermissionManager()                       { return permissionManager;  }
    public ConcurrentHashMap<String, ClientHandler> getConnectedClients() { return connectedClients;   }
    public ConcurrentHashMap<String, Room> getRooms()                     { return rooms;              }
    public boolean isRunning()                                            { return running;            }

    // -------------------------------------------------------------------------
    // Point d'entrée
    // -------------------------------------------------------------------------

    /**
     * Usage : java -jar server.jar [port]
     */
    public static void main(String[] args) {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        new GGServer(port).start();
    }
}
