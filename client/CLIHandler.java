package client;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import common.Color;

/**
 * CLIHandler — Thread de l'interface utilisateur en ligne de commande.
 *
 * Rôle :
 * - Affiche un menu d'aide et une invite (prompt) à l'utilisateur.
 * - Lit les commandes saisies et les traduit en messages GG envoyés au serveur
 *   ou aux pairs via P2PManager.
 * - Conformément à l'énoncé : "le client doit permettre l'envoi de n'importe
 *   quel type de message à n'importe quel moment".
 *
 * Hypothèses :
 * - L'interface est 100% CLI (stdin/stdout), aucune vérification de permission
 *   n'est effectuée : l'utilisateur est responsable des messages envoyés.
 * - Les commandes raccourcies (ex. "list") sont mappées vers le message GG complet.
 * - Une commande "raw" permet d'envoyer n'importe quelle chaîne brute.
 * - Les couleurs valides sont : RED, GREEN, BLUE, YELLOW, ORANGE.
 */
public class CLIHandler extends Thread {

    // ── Champs ──────────────────────────────────────────────────────────────

    /** Scanner sur stdin pour lire les commandes utilisateur. */
    private final Scanner scanner;

    /** Référence vers le client principal pour envoyer des messages. */
    private final GGClient client;

    // ── Constructeur ────────────────────────────────────────────────────────

    /**
     * @param client Instance de GGClient coordonnatrice.
     */
    public CLIHandler(GGClient client) {
        super("CLIHandler");
        this.client  = client;
        this.scanner = new Scanner(System.in, "UTF-8");
    }

    // ── Boucle principale ────────────────────────────────────────────────────

    @Override
    public void run() {
        printHelp();
        printPrompt();

        while (!isInterrupted()) {
            try {
                if (!scanner.hasNextLine()) break; // EOF (ex. pipe fermé)
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    printPrompt();
                    continue;
                }
                parseUserInput(line);
            } catch (IllegalStateException e) {
                // Scanner fermé
                break;
            }
        }
        System.out.println("[CLIHandler] Interface CLI terminée.");
    }

    // ── Traitement de la commande ─────────────────────────────────────────────

    /**
     * Analyse la saisie de l'utilisateur et déclenche l'action correspondante.
     * Toutes les commandes sont insensibles à la casse.
     *
     * @param line Ligne saisie par l'utilisateur.
     * @return La commande parsée (utile pour les tests).
     */
    public String parseUserInput(String line) {
        String[] tokens = line.split("\\s+");
        String   cmd    = tokens[0].toLowerCase();

        switch (cmd) {

            // ── Aide ─────────────────────────────────────────────────────
            case "help":
            case "h":
                printHelp();
                break;

            // ── Connexion / Déconnexion ───────────────────────────────────
            case "connect":
                // Usage : connect <ip> <port> <playerName>
                if (tokens.length < 4) { printUsage("connect <ip> <port> <playerName>"); break; }
                client.connect(tokens[1], tokens[2], Integer.parseInt(tokens[2]));
                break;

            case "disconnect":
            case "quit":
            case "exit":
                client.disconnect();
                interrupt();
                break;

            // ── Gestion des salles ───────────────────────────────────────
            case "create":
                // Usage : create <nom_salle> <max_joueurs> <max_tentatives>
                if (tokens.length < 4) { printUsage("create <salle> <maxJoueurs> <maxTentatives>"); break; }
                //client.sendToServer("GG|CREATE_ROOM|" + tokens[1] + "|" + tokens[2] + "|" + tokens[3]);
                client.sendToServer("GG|CREATE_ROOM|" + tokens[1] + "|" + tokens[2] + "|" + tokens[3] + "|" + client.getP2pPort());
                break;

            case "list":
                client.sendToServer("GG|LIST_ROOMS");
                break;

            case "join":
                // Usage : join <nom_salle>
                if (tokens.length < 2) { printUsage("join <nom_salle>"); break; }
                //client.sendToServer("GG|JOIN_ROOM|" + tokens[1]);
                client.sendToServer("GG|JOIN_ROOM|" + tokens[1] + "|" + client.getP2pPort());
                break;

            case "leave":
                // Usage : leave <nom_salle>
                if (tokens.length < 2) { printUsage("leave <nom_salle>"); break; }
                client.sendToServer("GG|LEAVE_ROOM|" + tokens[1]);
                break;

            case "kick":
                // Usage : kick <nom_salle> <nom_joueur>
                if (tokens.length < 3) { printUsage("kick <nom_salle> <nom_joueur>"); break; }
                client.sendToServer("GG|KICK_PLAYER|" + tokens[1] + "|" + tokens[2]);
                break;

            case "start":
                // Usage : start <nom_salle>
                if (tokens.length < 2) { printUsage("start <nom_salle>"); break; }
                client.sendToServer("GG|START_GAME|" + tokens[1]);
                break;

            // ── Jouer contre le serveur ───────────────────────────────────
            case "playserver":
                // Usage : playserver <tentatives>
                if (tokens.length < 2) { printUsage("playserver <tentatives>"); break; }
                client.sendToServer("GG|PLAY_SERVER|" + tokens[1]);
                break;

            // ── Messages de jeu P2P ──────────────────────────────────────
            case "secret":
    // Annonce que ce joueur possède le secret
    P2PManager p2p = client.getP2PManager();
    if (p2p != null) {
        // Informer les autres joueurs
        p2p.broadcast("GG|SECRET_SET|" + client.getPlayerName());
        
        // Informer le GameEngine local qu'on est détenteur
        // Créer un secret par défaut (l'utilisateur pourra le modifier plus tard)
        List<Color> defaultSecret = new ArrayList<>();
        defaultSecret.add(Color.RED);
        defaultSecret.add(Color.GREEN);
        defaultSecret.add(Color.BLUE);
        defaultSecret.add(Color.YELLOW);
        
        try {
            p2p.getGameEngine().setSecret(defaultSecret);
            System.out.println("[CLIHandler] Vous êtes maintenant détenteur du secret : " 
                    + p2p.getGameEngine().getSecretAsString());
        } catch (IllegalArgumentException e) {
            System.out.println("[CLIHandler] Erreur: " + e.getMessage());
        }
    } else {
        System.out.println("[CLIHandler] P2PManager non initialisé.");
    }
    break;

            case "guess":
                // Usage : guess <c1> <c2> <c3> <c4>
                if (tokens.length < 5) { printUsage("guess <RED|GREEN|BLUE|YELLOW|ORANGE> x4"); break; }
                String guess = "GG|GUESS|" + tokens[1].toUpperCase()
                             + "|" + tokens[2].toUpperCase()
                             + "|" + tokens[3].toUpperCase()
                             + "|" + tokens[4].toUpperCase();
                P2PManager p2pG = client.getP2PManager();
                if (p2pG != null) {
                    p2pG.sendGuess(java.util.Arrays.asList(
                            tokens[1], tokens[2], tokens[3], tokens[4]));
                } else {
                    client.sendToServer(guess); // Fallback : partie contre serveur
                }
                break;

            case "feedback":
                // Usage : feedback <couleurs_correctes> <positions_correctes>
                if (tokens.length < 3) { printUsage("feedback <correctColors> <correctPositions>"); break; }
                int correct = Integer.parseInt(tokens[1]);
                int placed  = Integer.parseInt(tokens[2]);
                P2PManager p2pF = client.getP2PManager();
                if (p2pF != null) {
                    p2pF.sendFeedback(correct, placed);
                } else {
                    client.sendToServer("GG|FEEDBACK|" + correct + "|" + placed);
                }
                break;

            case "winner":
                // Usage : winner <nom_joueur>
                if (tokens.length < 2) { printUsage("winner <nom_joueur>"); break; }
                P2PManager p2pW = client.getP2PManager();
                if (p2pW != null) {
                    p2pW.broadcast("GG|WINNER|" + tokens[1]);
                }
                break;

            case "newgame":
                P2PManager p2pN = client.getP2PManager();
                if (p2pN != null) {
                    p2pN.broadcast("GG|NEW_GAME");
                }
                break;

            // ── Envoi brut (liberté totale) ───────────────────────────────
            case "raw":
                // Usage : raw GG|WHATEVER|param1|param2
                if (tokens.length < 2) { printUsage("raw <message_complet>"); break; }
                // Reconstitue le message complet à partir du 2e token
                String rawMsg = line.substring(line.indexOf(' ') + 1);
                client.sendToServer(rawMsg);
                break;

            // ── Commande inconnue ─────────────────────────────────────────
            default:
                System.out.println("[CLIHandler] Commande inconnue : '" + cmd + "'. Tapez 'help' pour l'aide.");
        }

        printPrompt();
        return cmd;
    }

    // ── Affichage ────────────────────────────────────────────────────────────

    /**
     * Affiche l'aide complète avec toutes les commandes disponibles.
     */
    public void printHelp() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                  GUESS GAME — Client CLI                 ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  CONNEXION                                               ║");
        System.out.println("║    connect <ip> <port> <nom>  Connexion au serveur       ║");
        System.out.println("║    disconnect / quit          Déconnexion                ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  SALLES                                                  ║");
        System.out.println("║    list                       Liste des salles           ║");
        System.out.println("║    create <salle> <max> <t>   Créer une salle            ║");
        System.out.println("║    join   <salle>             Rejoindre une salle        ║");
        System.out.println("║    leave  <salle>             Quitter une salle          ║");
        System.out.println("║    kick   <salle> <joueur>    Expulser un joueur         ║");
        System.out.println("║    start  <salle>             Démarrer la partie         ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  JEU (P2P)                                               ║");
        System.out.println("║    playserver <tentatives>    Jouer contre le serveur    ║");
        System.out.println("║    secret                     Annoncer son secret        ║");
        System.out.println("║    guess  <c1> <c2> <c3> <c4> Proposer une combinaison v ║");
        System.out.println("║      Couleurs : RED GREEN BLUE YELLOW ORANGE             ║");
        System.out.println("║    feedback <couleurs> <pos>   Donner le feedback        ║");
        System.out.println("║    winner <nom>               Annoncer le gagnant        ║");
        System.out.println("║    newgame                    Lancer un nouveau jeu      ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  AVANCÉ                                                  ║");
        System.out.println("║    raw <message_GG_complet>   Envoyer n'importe quoi     ║");
        System.out.println("║    help                       Afficher cette aide        ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * Affiche l'invite de commande (prompt).
     */
    public void printPrompt() {
        System.out.print("[" + client.getPlayerName() + "] > ");
        System.out.flush();
    }

    /**
     * Affiche un message d'usage pour une commande mal saisie.
     *
     * @param usage La syntaxe attendue de la commande.
     */
    private void printUsage(String usage) {
        System.out.println("[CLIHandler] Usage : " + usage);
    }
}