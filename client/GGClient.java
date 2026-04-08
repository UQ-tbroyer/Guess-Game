package client;

import common.DebugLogger;
import common.MessageParser;
import java.io.*;
import java.net.*;

/**
 * GGClient — Point d'entrée principal du client Guess Game.
 *
 * Hypothèses :
 * - L'IP et le port du serveur sont passés en arguments de ligne de commande.
 *   Exemple : java -jar client.jar 127.0.0.1 5000 Alice
 * - Si aucun argument n'est fourni, des valeurs par défaut sont utilisées
 *   (localhost:5000, joueur "Player").
 * - GGClient coordonne ServerListener (écoute passive du serveur) et
 *   CLIHandler (interface utilisateur en ligne de commande).
 * - La connexion initiale GG|CONNECT est envoyée dès que le socket est établi.
 */
public class GGClient {

    // ── Champs ──────────────────────────────────────────────────────────────

    private final String serverIp;
    private final int serverPort;
    private Socket serverSocket;
    private String playerName;
    private boolean connected = false;

    private P2PManager p2pManager;
    private CLIHandler cliHandler;
    // ServerListener est démarré dans connect() et gardé en référence pour join()
    private ServerListener serverListener;

    private volatile boolean playingServerGame = false;
    private volatile String currentRoom = null;

    // ── Constructeur ────────────────────────────────────────────────────────

    public GGClient(String serverIp, int serverPort, String playerName) {
        this.serverIp   = serverIp;
        this.serverPort = serverPort;
        this.playerName = playerName;
    }

    // ── API publique ────────────────────────────────────────────────────────

    /**
     * Établit la connexion TCP avec le serveur, envoie GG|CONNECT,
     * puis démarre ServerListener et CLIHandler.
     */
    public void connect(String ip, String port, int portInt) {
        try {
            serverSocket = new Socket(ip, portInt);
            System.out.println("[GGClient] Connecté au serveur " + ip + ":" + portInt);

            // Initialisation du gestionnaire P2P
            p2pManager = new P2PManager(playerName, this);
            try {
                p2pManager.startListening(0); // port dynamique pour P2P
                System.out.println("[GGClient] P2P écoute sur le port " + p2pManager.getListeningPort());
            } catch (IOException e) {
                System.err.println("[GGClient] impossible de démarrer le serveur P2P : " + e.getMessage());
            }

            // Démarrage du thread d'écoute du serveur
            serverListener = new ServerListener(serverSocket, this);
            serverListener.start();

            // Envoi du message de connexion initial
            sendToServer("GG|CONNECT|" + playerName);

            // Démarrage de l'interface CLI (bloquant jusqu'à déconnexion)
            cliHandler = new CLIHandler(this);
            cliHandler.start();

            // Attente de la fin des threads avant de quitter
            try {
                cliHandler.join();
                serverListener.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } catch (IOException e) {
            System.err.println("[GGClient] Impossible de se connecter au serveur : " + e.getMessage());
        }
    }

    /**
     * Surcharge de connect() utilisant les champs de l'instance.
     */
    public void connect() {
        connect(serverIp, String.valueOf(serverPort), serverPort);
    }

    /**
     * Envoie un message texte au serveur via le socket TCP.
     * Hypothèse : les messages sont encodés en UTF-8 et terminés par \n.
     *
     * @param msg Le message à envoyer (ex. "GG|LIST_ROOMS")
     */
    public void sendToServer(String msg) {
        try {
            if (serverSocket == null || serverSocket.isClosed()) {
                System.err.println("[GGClient] Socket fermé, impossible d'envoyer : " + msg);
                return;
            }
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(serverSocket.getOutputStream(), "UTF-8"), true);
            out.println(msg);
            System.out.println("[GGClient] → Serveur : " + msg);
        } catch (IOException e) {
            System.err.println("[GGClient] Erreur d'envoi : " + e.getMessage());
        }
    }

    /**
     * Ferme proprement le socket et arrête les threads associés.
     */
    public void disconnect() {
        try {
            if (serverListener != null) serverListener.interrupt();
            if (cliHandler    != null) cliHandler.interrupt();
            if (serverSocket  != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            playingServerGame = false;
            System.out.println("[GGClient] Déconnecté du serveur.");
        } catch (IOException e) {
            System.err.println("[GGClient] Erreur lors de la déconnexion : " + e.getMessage());
        }
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public String getPlayerName() {
        if (!connected || playerName == null || playerName.isBlank()) {
            return "[NON_CONNECTE]";
        }
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public P2PManager getP2PManager()      { return p2pManager;  }
    public void       setP2PManager(P2PManager p2p) { this.p2pManager = p2p; }
    public int        getP2PPort()          { return p2pManager != null ? p2pManager.getListeningPort() : 0; }
    public CLIHandler getCLIHandler()      { return cliHandler;  }
    public boolean    isPlayingServerGame() { return playingServerGame; }
    public void       setPlayingServerGame(boolean playing) { playingServerGame = playing; }
    public String     getCurrentRoom()      { return currentRoom; }
    public void       setCurrentRoom(String room) { this.currentRoom = room; }
    /**
     * Notifie le serveur de la fin de partie pour la salle courante (P2P).
     */
    public void notifyServerGameOver(String result, String winner) {
        if (serverSocket == null || serverSocket.isClosed()) {
            System.err.println("[GGClient] Impossible d'envoyer GAME_OVER au serveur : socket fermé.");
            return;
        }
        sendToServer(MessageParser.serialize(common.CommandType.GAME_OVER, result, winner));
    }
    // ── Point d'entrée ───────────────────────────────────────────────────────

    /**
     * main() — Point d'entrée du programme client.
     *
     * Usage : java -jar client.jar [ip] [port] [playerName]
     */
    public static void main(String[] args) {
        String ip         = (args.length > 0) ? args[0] : "127.0.0.1";
        int    port       = 5000;
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("[GGClient] Port invalide : '" + args[1] + "'. Utilisation du port par défaut 5000.");
            }
        }
        String playerName = (args.length > 2) ? args[2] : "Player";

        // Activer les logs debug via --debug (optionnel)
        boolean debug = false;
        for (String arg : args) {
            if ("--debug".equalsIgnoreCase(arg)) { debug = true; break; }
        }
        DebugLogger.getInstance().setEnabled(debug);

        GGClient client = new GGClient(ip, port, playerName);
        client.connect();
    }
}