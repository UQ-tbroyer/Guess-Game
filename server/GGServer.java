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
 */
public class GGServer {

    public static final int DEFAULT_PORT    = 5000;
    private static final int THREAD_POOL_SIZE = 50;

    private final ConcurrentHashMap<String, ClientHandler> connectedClients =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Room> rooms =
            new ConcurrentHashMap<>();

    // Sessions solo (PLAY_SERVER) par joueur
    private final ConcurrentHashMap<String, GameSession> soloSessions =
            new ConcurrentHashMap<>();

    private final PermissionManager permissionManager = new PermissionManager();
    private final SecurityManager securityManager = new SecurityManager();

    private final int port;
    private ServerSocket serverSocket;
    private final ExecutorService threadPool;
    private volatile boolean running = false;

    public GGServer(int port) {
        this.port       = port;
        this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

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

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[GGServer] Nouvelle connexion : "
                        + clientSocket.getInetAddress().getHostAddress()
                        + ":" + clientSocket.getPort());

                try {
                    clientSocket = securityManager.wrapTLS(clientSocket);
                } catch (IOException e) {
                    System.err.println("[GGServer] TLS handshake échoué avec "
                            + clientSocket.getInetAddress().getHostAddress() + " : " + e.getMessage());
                    try { clientSocket.close(); } catch (IOException ignored) {}
                    continue;
                }

                ClientHandler handler = new ClientHandler(clientSocket, this);
                threadPool.submit(handler);

            } catch (IOException e) {
                if (running) {
                    System.err.println("[GGServer] Erreur d'acceptation : " + e.getMessage());
                }
            }
        }
    }

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

    public synchronized boolean registerClient(String playerName, ClientHandler handler) {
        if (playerName == null || playerName.isBlank() || handler == null) {
            return false;
        }

        // Ne pas autoriser deux joueurs avec le même nom (cas in-sensible aussi)
        for (String existingName : connectedClients.keySet()) {
            if (existingName.equalsIgnoreCase(playerName)) {
                return false;
            }
        }

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
        if (playerName == null) return false;
        for (String existingName : connectedClients.keySet()) {
            if (existingName.equalsIgnoreCase(playerName)) {
                return true;
            }
        }
        return false;
    }

    public Room createRoom(String roomName, int maxPlayers, int maxAttempts, String adminName) {
        ClientHandler creatorHandler = getClient(adminName);
        if (creatorHandler == null) {
            return null;
        }
        Room newRoom  = new Room(roomName, maxPlayers, maxAttempts, creatorHandler);
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

    public GameSession createSoloSession(String playerName, int maxAttempts) {
        if (soloSessions.containsKey(playerName)) {
            return null; // session déjà en cours pour ce joueur
        }
        GameSession session = new GameSession(playerName, maxAttempts);
        session.generateSecret();
        soloSessions.put(playerName, session);
        System.out.println("[GGServer] Session solo démarrée pour " + playerName + " (" + maxAttempts + " tentatives)");
        return session;
    }

    public GameSession getSoloSession(String playerName) {
        return soloSessions.get(playerName);
    }

    public void removeSoloSession(String playerName) {
        soloSessions.remove(playerName);
    }

    public String getRoomListAsString() {
        if (rooms.isEmpty()) return "";
        return String.join(",", rooms.keySet());
    }

    public PermissionManager getPermissionManager()                       { return permissionManager;  }
    public SecurityManager getSecurityManager()                           { return securityManager;    }
    public ConcurrentHashMap<String, ClientHandler> getConnectedClients() { return connectedClients;   }
    public ConcurrentHashMap<String, Room> getRooms()                     { return rooms;              }
    public boolean isRunning()                                            { return running;            }

    public static void main(String[] args) {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        new GGServer(port).start();
    }
}