package client;

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
	private String currentRoom;
    private String serverIp;
    private int serverPort;
    private int p2pPort;
    private Socket serverSocket;
    private String playerName;

    private P2PManager p2pManager;
    private CLIHandler cliHandler;
    // ServerListener est démarré dans connect() et gardé en référence pour join()
    private ServerListener serverListener;

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
        p2pManager = new P2PManager(playerName);

        try {
            // Démarrer le serveur P2P sur un port libre
            p2pManager.startListening(0);
            p2pPort = p2pManager.getLocalPort();
            System.out.println("[GGClient] Serveur P2P démarré sur le port " + p2pPort);
        } catch (IOException e) {
            System.err.println("[GGClient] Impossible de démarrer le serveur P2P: " + e.getMessage());
            p2pPort = 0;
        }

        // Démarrage du thread d'écoute du serveur
        serverListener = new ServerListener(serverSocket, this);
        serverListener.start();

        // Envoi du message de connexion initial
        sendToServer("GG|CONNECT|" + playerName);

        // Démarrage de l'interface CLI
        cliHandler = new CLIHandler(this);
        cliHandler.start();

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
            System.out.println("[GGClient] Déconnecté du serveur.");
        } catch (IOException e) {
            System.err.println("[GGClient] Erreur lors de la déconnexion : " + e.getMessage());
        }
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public String     getPlayerName()  { return playerName;  }
    public P2PManager getP2PManager()  { return p2pManager;  }
    public CLIHandler getCLIHandler()  { return cliHandler;  }
    public int getP2pPort() { return p2pPort; }

    // ── Point d'entrée ───────────────────────────────────────────────────────

    /**
     * main() — Point d'entrée du programme client.
     *
     * Usage : java -jar client.jar [ip] [port] [playerName]
     */
    public static void main(String[] args) {
        String ip         = (args.length > 0) ? args[0] : "127.0.0.1";
        int    port       = (args.length > 1) ? Integer.parseInt(args[1]) : 5000;
        String playerName = (args.length > 2) ? args[2] : "bob";

        GGClient client = new GGClient(ip, port, playerName);
        client.connect();
    }

	public String getCurrentRoom() {
		// TODO Auto-generated method stub
		return currentRoom;
	}

	public void setCurrentRoom(String string) {
		// TODO Auto-generated method stub
		currentRoom = string;
	}
}