package client;

import common.Color;
import common.ParseException;
import java.util.Scanner;

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
            } catch (RuntimeException e) {
                System.err.println("[CLIHandler] Erreur inattendue : " + e.getMessage());
                printPrompt();
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
                // Sanitisation : retire | \n \r du nom de joueur pour éviter l'injection de protocole.
                String playerNameRaw = tokens[3].replace("|", "").replace("\n", "").replace("\r", "").trim();
                if (playerNameRaw.isEmpty()) { System.out.println("[CLIHandler] Nom de joueur invalide."); break; }
                client.setPlayerName(playerNameRaw);
                client.setConnected(false);
                try {
                    client.connect(tokens[1], tokens[2], Integer.parseInt(tokens[2]));
                } catch (NumberFormatException e) {
                    System.out.println("[CLIHandler] Port invalide : '" + tokens[2] + "'. Entrez un nombre entier.");
                }
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
                // Sanitisation du nom de salle
                String roomNameCreate = tokens[1].replace("|", "").replace("\n", "").replace("\r", "").trim();
                if (roomNameCreate.isEmpty()) { System.out.println("[CLIHandler] Nom de salle invalide."); break; }
                int p2pPortCreate = client.getP2PPort();
                String createMsg = "GG|CREATE_ROOM|" + roomNameCreate + "|" + tokens[2] + "|" + tokens[3];
                if (p2pPortCreate > 0) {
                    createMsg += "|" + p2pPortCreate;
                }
                client.sendToServer(createMsg);
                break;

            case "list":
                client.sendToServer("GG|LIST_ROOMS");
                break;

            case "join":
                // Usage : join <nom_salle>
                if (tokens.length < 2) { printUsage("join <nom_salle>"); break; }
                // Sanitisation du nom de salle
                String roomNameJoin = tokens[1].replace("|", "").replace("\n", "").replace("\r", "").trim();
                if (roomNameJoin.isEmpty()) { System.out.println("[CLIHandler] Nom de salle invalide."); break; }
                int p2pPortJoin = client.getP2PPort();
                String joinMsg = "GG|JOIN_ROOM|" + roomNameJoin;
                if (p2pPortJoin > 0) {
                    joinMsg += "|" + p2pPortJoin;
                }
                client.sendToServer(joinMsg);
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

            case "quitserver":
                // Quitte la partie solo en cours
                if (!client.isPlayingServerGame()) {
                    System.out.println("[CLIHandler] Vous n'\u00eates pas en mode solo.");
                    break;
                }
                client.sendToServer("GG|GAME_OVER|QUIT|" + client.getPlayerName());
                client.setPlayingServerGame(false);
                System.out.println("[GAME] Partie solo abandonn\u00e9e.");
                break;

            // ── Messages de jeu P2P ──────────────────────────────────────
            case "secret":
                // Usage : secret <c1> <c2> <c3> <c4>
                // Ex : secret RED GREEN BLUE YELLOW
                P2PManager p2pSec = client.getP2PManager();
                if (p2pSec == null) {
                    System.out.println("[CLIHandler] P2PManager non initialisé.");
                    break;
                }

                if (!p2pSec.canSetSecret(client.getPlayerName())) {
                    System.out.println("[CLIHandler] Seul l'admin peut définir le secret pour cette manche.");
                    break;
                }

                if (p2pSec.getGameEngine().isSecretOwner()) {
                    System.out.println("[CLIHandler] Secret déjà défini pour cette manche. Attendez 'newgame'.");
                    break;
                }

                switch (tokens.length) {
                    case 1 -> {
                        p2pSec.generateRandomSecret();
                        p2pSec.setAdminIsPlayer(true);
                        p2pSec.broadcast("GG|SECRET_SET|" + client.getPlayerName() + "|RANDOM");
                        p2pSec.announceCurrentTurnIfSecretDefined();
                        String firstPlayer = p2pSec.getCurrentTurnPlayer();
                        if (firstPlayer != null) {
                            if (firstPlayer.equals(client.getPlayerName())) {
                                System.out.println("[GAME] La partie commence ! C'est votre tour de deviner.");
                            } else {
                                System.out.println("[GAME] La partie commence ! C'est le tour de " + firstPlayer + " de débuter.");
                            }
                        }
                        System.out.println("[CLIHandler] Secret aléatoire généré. Vous êtes aussi joueur.");
                    }
                    case GameEngine.COMBINATION_SIZE + 1 -> {
                        try {
                            java.util.List<Color> combo = new java.util.ArrayList<>();
                            for (int i = 1; i <= GameEngine.COMBINATION_SIZE; i++) {
                                combo.add(Color.fromString(tokens[i]));
                            }
                            p2pSec.setSecret(combo);
                            p2pSec.broadcast("GG|SECRET_SET|" + client.getPlayerName());
                            p2pSec.removePlayerFromTurnOrder(client.getPlayerName());
                            p2pSec.announceCurrentTurnIfSecretDefined();
                            String firstPlayer = p2pSec.getCurrentTurnPlayer();
                            if (firstPlayer != null) {
                                System.out.println("[GAME] La partie commence ! C'est le tour de " + firstPlayer + " de débuter.");
                            }
                            System.out.println("[CLIHandler] Secret défini. Vous n'êtes pas joueur cette manche. Envoyé aux pairs.");
                        } catch (ParseException e) {
                            System.out.println("[CLIHandler] Couleur invalide : " + e.getMessage());
                        } catch (IllegalStateException | IllegalArgumentException e) {
                            System.out.println("[CLIHandler] Erreur définition secret : " + e.getMessage());
                        }
                    }
                    default -> printUsage("secret <c1> <c2> <c3> <c4> (couleurs : RED, GREEN, BLUE, YELLOW, ORANGE)");
                }
                break;

            case "guess":
                // Usage : guess <c1> <c2> <c3> <c4>
                if (tokens.length < 5) { printUsage("guess <RED|GREEN|BLUE|YELLOW|ORANGE> x4"); break; }
                if (client.isPlayingServerGame()) {
                    client.sendToServer("GG|GUESS|" + tokens[1].toUpperCase()
                                       + "|" + tokens[2].toUpperCase()
                                       + "|" + tokens[3].toUpperCase()
                                       + "|" + tokens[4].toUpperCase());
                    // Suivi local des tentatives pour affichage
                    P2PManager p2pSolo = client.getP2PManager();
                    if (p2pSolo != null) p2pSolo.getGameEngine().consumeAttempt();
                } else {
                    P2PManager p2pG = client.getP2PManager();
                    if (p2pG != null) {
                        if (!p2pG.isPlayerTurn(client.getPlayerName())) {
                            String currentTurn = p2pG.getCurrentTurnPlayer();
                            if (currentTurn != null) {
                                System.out.println("[CLIHandler] Ce n'est pas votre tour. Attendez le tour de " + currentTurn + ".");
                            } else {
                                System.out.println("[CLIHandler] Aucune partie en cours.");
                            }
                            break;
                        }
                        p2pG.sendGuess(java.util.Arrays.asList(
                                tokens[1], tokens[2], tokens[3], tokens[4]));
                    } else {
                        client.sendToServer("GG|GUESS|" + tokens[1].toUpperCase()
                                           + "|" + tokens[2].toUpperCase()
                                           + "|" + tokens[3].toUpperCase()
                                           + "|" + tokens[4].toUpperCase());
                    }
                }
                break;

            case "feedback":
                // Usage : feedback <couleurs_correctes> <positions_correctes>
                if (tokens.length < 3) { printUsage("feedback <correctColors> <correctPositions>"); break; }
                try {
                    int correct = Integer.parseInt(tokens[1]);
                    int placed  = Integer.parseInt(tokens[2]);
                    P2PManager p2pF = client.getP2PManager();
                    if (p2pF != null) {
                        p2pF.sendFeedback(correct, placed);
                    } else {
                        client.sendToServer("GG|FEEDBACK|" + correct + "|" + placed);
                    }
                } catch (NumberFormatException e) {
                    System.out.println("[CLIHandler] Valeur invalide. Usage : feedback <entier> <entier>");
                }
                break;

            case "winner":
                // Usage : winner <nom_joueur>
                if (tokens.length < 2) { printUsage("winner <nom_joueur>"); break; }
                P2PManager p2pW = client.getP2PManager();
                if (p2pW != null) {
                    p2pW.announceWinner(tokens[1]);
                }
                break;

            case "newgame":
                P2PManager p2pN = client.getP2PManager();
                if (p2pN != null) {
                    p2pN.resetForNewGame();
                    p2pN.broadcast("GG|NEW_GAME");
                    // L'admin ne reçoit pas son propre broadcast — informer localement
                    if (p2pN.getPlayerName().equals(p2pN.getRoomAdminName())) {
                        System.out.println("[GAME] Nouvelle manche lancée. Définissez le secret avec 'secret' ou 'secret c1 c2 c3 c4'.");
                        System.out.println("[GAME] Vous pouvez aussi changer le nombre de tentatives avec 'setattempts <n>' avant de définir le secret.");
                    }
                }
                break;

            case "setattempts":
                // Usage : setattempts <n>
                // Admin seulement, avant que le secret soit défini
                P2PManager p2pA = client.getP2PManager();
                if (p2pA == null) {
                    System.out.println("[CLIHandler] P2PManager non initialisé.");
                    break;
                }
                if (!p2pA.canSetSecret(client.getPlayerName())) {
                    System.out.println("[CLIHandler] Seul l'admin peut changer le nombre de tentatives.");
                    break;
                }
                if (p2pA.getCurrentSecretOwner() != null) {
                    System.out.println("[CLIHandler] Le secret est déjà défini. Attendez 'newgame' pour changer les tentatives.");
                    break;
                }
                if (tokens.length < 2) { printUsage("setattempts <nombre>"); break; }
                try {
                    int newAttempts = Integer.parseInt(tokens[1]);
                    p2pA.getGameEngine().setMaxAttempts(newAttempts);
                    p2pA.broadcast("GG|SET_ATTEMPTS|" + newAttempts);
                    System.out.println("[GAME] Nombre de tentatives fixé à " + newAttempts + ". Notifié à tous les joueurs.");
                } catch (NumberFormatException e) {
                    System.out.println("[CLIHandler] Nombre invalide : '" + tokens[1] + "'. Entrez un entier positif.");
                } catch (IllegalArgumentException e) {
                    System.out.println("[CLIHandler] Valeur invalide : " + e.getMessage());
                }
                break;

            // ── Envoi brut (liberté totale) ───────────────────────────────
            case "raw":
                // Usage : raw GG|WHATEVER|param1|param2
                // Route automatiquement vers P2P ou serveur selon le type de commande.
                if (tokens.length < 2) { printUsage("raw <message_complet>"); break; }
                String rawMsg = line.substring(line.indexOf(' ') + 1);
                routeRawMessage(rawMsg);
                break;

            // ── Commande inconnue ─────────────────────────────────────────
            default:
                System.out.println("[CLIHandler] Commande inconnue : '" + cmd + "'. Tapez 'help' pour l'aide.");
        }

        printPrompt();
        return cmd;
    }

    // ── Routage des messages bruts ────────────────────────────────────────────

    /**
     * Route un message GG brut vers P2P ou serveur selon le type de commande.
     *
     * Commandes P2P (GUESS intégré, broadcast pour les autres) :
     *   GUESS, SECRET_SET, FEEDBACK, WINNER, NEW_GAME, NEXT_TURN,
     *   TURN_ANNOUNCEMENT, PLAYER_OUT, SET_ATTEMPTS, HELLO
     *
     * Tout le reste part au serveur (CONNECT, CREATE_ROOM, JOIN_ROOM, etc.)
     */
    private void routeRawMessage(String rawMsg) {
        if (rawMsg == null || rawMsg.isEmpty()) return;

        String[] parts = rawMsg.split("\\|");
        if (parts.length < 2 || !parts[0].equals("GG")) {
            // Pas un message GG valide — envoyer tel quel au serveur
            client.sendToServer(rawMsg);
            return;
        }

        String type = parts[1].toUpperCase();
        P2PManager p2p = client.getP2PManager();

        switch (type) {
            case "GUESS" -> {
                if (client.isPlayingServerGame()) {
                    // Mode solo : passe au serveur
                    client.sendToServer(rawMsg);
                    if (p2p != null) p2p.getGameEngine().consumeAttempt();
                } else if (p2p != null) {
                    // Mode P2P : extraire les couleurs et passer par sendGuess()
                    // pour respecter la logique de tour et waitingForFeedback
                    if (parts.length >= 6) {
                        p2p.sendGuess(java.util.Arrays.asList(
                                parts[2], parts[3], parts[4], parts[5]));
                    } else {
                        System.out.println("[CLIHandler] raw GUESS : format attendu GG|GUESS|c1|c2|c3|c4");
                    }
                } else {
                    client.sendToServer(rawMsg);
                }
            }
            case "SECRET_SET", "FEEDBACK", "WINNER", "NEW_GAME",
                 "NEXT_TURN", "TURN_ANNOUNCEMENT", "PLAYER_OUT",
                 "SET_ATTEMPTS", "HELLO" -> {
                if (p2p != null) {
                    p2p.broadcast(rawMsg);
                    System.out.println("[raw] Diffusé en P2P : " + rawMsg);
                } else {
                    System.out.println("[CLIHandler] P2PManager non initialisé, message non envoyé.");
                }
            }
            default -> {
                // Commandes serveur (lobby) : envoyer directement
                client.sendToServer(rawMsg);
            }
        }
    }

    // ── Affichage ────────────────────────────────────────────────────────────

    /**
     * Affiche l'aide complète avec toutes les commandes disponibles.
     */
    public void printHelp() {
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║               GUESS GAME -- Client CLI                        ║");
        System.out.println("╠════════════════════════════════════════════════════════════════╣");
        System.out.println("║  CONNEXION                                                    ║");
        System.out.println("║    connect <ip> <port> <nom>   Se connecter au serveur        ║");
        System.out.println("║    disconnect / quit / exit    Se deconnecter                 ║");
        System.out.println("╠════════════════════════════════════════════════════════════════╣");
        System.out.println("║  SALLES                                                       ║");
        System.out.println("║    list                        Lister les salles disponibles  ║");
        System.out.println("║    create <salle> <max> <tent> Creer une salle                ║");
        System.out.println("║      ex: create room1 4 5  -> 4 joueurs max, 5 tentatives      ║");
        System.out.println("║    join  <salle>               Rejoindre une salle             ║");
        System.out.println("║    leave <salle>               Quitter la salle                ║");
        System.out.println("║    kick  <salle> <joueur>      Expulser un joueur (admin)      ║");
        System.out.println("║    start <salle>               Demarrer la partie (admin)      ║");
        System.out.println("╠════════════════════════════════════════════════════════════════╣");
        System.out.println("║  JEU P2P (apres start)                                        ║");
        System.out.println("║    secret                      Secret aleatoire -- admin joue ║");
        System.out.println("║    secret <c1> <c2> <c3> <c4> Definir le secret (admin ne     ║");
        System.out.println("║                                joue pas)                      ║");
        System.out.println("║    guess  <c1> <c2> <c3> <c4> Proposer une combinaison        ║");
        System.out.println("║      Couleurs : RED  GREEN  BLUE  YELLOW  ORANGE              ║");
        System.out.println("║    feedback <couleurs> <pos>   Feedback manuel (admin)         ║");
        System.out.println("║    winner <nom>                Annoncer le gagnant (admin)     ║");
        System.out.println("║    newgame                     Nouvelle manche (admin)         ║");
        System.out.println("║    setattempts <n>             Changer les tentatives (admin,  ║");
        System.out.println("║                                avant le secret)                ║");
        System.out.println("╠════════════════════════════════════════════════════════════════╣");
        System.out.println("║  JEU SOLO (contre le serveur)                                 ║");
        System.out.println("║    playserver <tentatives>     Lancer une partie solo          ║");
        System.out.println("║    guess  <c1> <c2> <c3> <c4> Proposer une combinaison        ║");
        System.out.println("║    quitserver                  Abandonner la partie solo       ║");
        System.out.println("╠════════════════════════════════════════════════════════════════╣");
        System.out.println("║  AVANCE                                                        ║");
        System.out.println("║    raw <message_GG_complet>    Envoyer un message brut         ║");
        System.out.println("║      ex: raw GG|LIST_ROOMS                                    ║");
        System.out.println("║    help / h                    Afficher cette aide             ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
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