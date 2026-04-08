package client;

import common.Color;
import common.CommandType;
import common.DebugLogger;
import common.Message;
import common.MessageParser;
import common.ParseException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * PeerListener — thread d'écoute dédié à un pair P2P.
 *
 * Responsabilités :
 *  - Lire en continu les messages GG entrants depuis un pair connecté.
 *  - Dispatcher chaque message vers le handler approprié.
 *  - Notifier P2PManager en cas de déconnexion inattendue.
 *
 * Messages P2P gérés :
 *  - GG|SECRET_SET|nom_joueur   → identifie le détenteur du secret
 *  - GG|GUESS|c1|c2|c3|c4       → proposition reçue (si ce client est le détenteur)
 *  - GG|FEEDBACK|cc|cp           → retour sur notre proposition
 *  - GG|WINNER|nom_joueur        → annonce de la victoire
 *  - GG|NEW_GAME                 → réinitialisation de la manche
 *
 * Hypothèses :
 *  - Un PeerListener est créé par P2PManager pour chaque pair (entrant ou sortant).
 *  - Si une IOException se produit, la connexion est considérée perdue.
 *  - Le premier message d'un pair entrant non encore identifié permet son enregistrement.
 */
public class PeerListener extends Thread {

    // -------------------------------------------------------------------------
    // Attributs
    // -------------------------------------------------------------------------

    private final Socket      peerSocket;
    private       String      peerName;
    private final BufferedReader in;
    private final P2PManager  p2pManager;
    private final GameEngine  gameEngine;

    private final DebugLogger logger = DebugLogger.getInstance();

    // -------------------------------------------------------------------------
    // Constructeur
    // -------------------------------------------------------------------------

    /**
     * @param peerSocket  socket de connexion avec le pair
     * @param p2pManager  gestionnaire P2P parent
     * @param gameEngine  moteur de jeu local partagé
     * @throws IOException si le flux d'entrée ne peut pas être ouvert
     */
    public PeerListener(Socket peerSocket, P2PManager p2pManager, GameEngine gameEngine)
            throws IOException {
        this.peerSocket  = peerSocket;
        this.p2pManager  = p2pManager;
        this.gameEngine  = gameEngine;
        this.in          = new BufferedReader(
                new InputStreamReader(peerSocket.getInputStream(), "UTF-8"));
        this.peerName    = null;
        setName("PeerListener-" + peerSocket.getInetAddress().getHostAddress());
        setDaemon(true);
    }

    // -------------------------------------------------------------------------
    // Boucle principale
    // -------------------------------------------------------------------------

    @Override
    public void run() {
        logger.logEvent("PeerListener démarré : "
                + peerSocket.getInetAddress().getHostAddress());
        try {
            String rawLine;
            while ((rawLine = in.readLine()) != null) {
                String trimmed = rawLine.trim();
                handleRawLine(trimmed);
            }
        } catch (IOException e) {
            logger.logError("PeerListener : connexion perdue avec "
                    + (peerName != null ? peerName : peerSocket.getInetAddress().getHostAddress()), e);
        } finally {
            closeSilently();
            logger.logEvent("PeerListener terminé : "
                    + (peerName != null ? peerName : "pair inconnu"));
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
            logger.logError("PeerListener : message P2P invalide ignoré : '" + rawLine + "'", e);
            return;
        }

        logger.logIncoming(msg);

        switch (msg.getCommand()) {
            case HELLO      -> onHello(msg);
            case SECRET_SET -> onSecretSet(msg);
            case GUESS      -> onGuess(msg);
            case FEEDBACK   -> onFeedback(msg);
            case WINNER     -> onWinner(msg);
            case GAME_OVER  -> onGameOver(msg);
            case NEW_GAME   -> onNewGame();
            case NEXT_TURN  -> onNextTurn(msg);
            case TURN_ANNOUNCEMENT -> onTurnAnnouncement(msg);
            case PLAYER_OUT -> onPlayerOut(msg);
            default         -> logger.logError(
                    "PeerListener : commande P2P inattendue : " + msg.getCommand());
        }
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /**
     * GG|HELLO|nom_joueur — le pair sortant s'identifie pour que nous puissions
     * l'enregistrer dans la map peers et lui répondre correctement.
     */
    private void onHello(Message msg) {
        try {
            msg.requireFields(1);
            String remoteName = msg.getField(0);
            if (peerName == null) peerName = remoteName;
            p2pManager.registerIncomingPeer(remoteName, peerSocket);
            logger.logEvent("PeerListener : HELLO reçu de " + remoteName);
        } catch (ParseException e) {
            logger.logError("PeerListener : HELLO mal formé.", e);
        }
    }

    /**
     * GG|SECRET_SET|nom_joueur
     * Identifie le détenteur du secret et met à jour P2PManager.
     */
    private void onSecretSet(Message msg) {
        try {
            msg.requireFields(1);
            String owner = msg.getField(0);

            // Vérifier si le marqueur RANDOM est présent (Cas 1 : admin joueur)
            boolean isRandom = msg.getFieldCount() >= 2 && "RANDOM".equalsIgnoreCase(msg.getField(1));

            // Identification du pair entrant si pas encore connu
            if (peerName == null) {
                peerName = owner;
                p2pManager.registerPeer(peerName, peerSocket);
            } else if (!peerName.equals(owner)) {
                // on peut garder l'ancien id (peut être le nom réel), mais on log l'anomalie
                logger.logEvent("PeerListener : SECRET_SET reçu de " + owner + " alors qu'on attendait " + peerName);
            }

            // Si un secret est déjà défini et la manche n'est pas terminée, ignorer la mise à jour
            if (p2pManager.getCurrentSecretOwner() != null && !gameEngine.isGameOver()
                    && !p2pManager.getCurrentSecretOwner().equals(owner)) {
                logger.logEvent("PeerListener : SECRET_SET ignoré car secret déjà défini par "
                        + p2pManager.getCurrentSecretOwner());
                return;
            }

            // Si le secret doit être posé uniquement par l'admin et que ce n'est pas lui,
            // ignorer la commande tant que la manche n'est pas finie.
            if (p2pManager.isSecretRestrictedToAdmin()
                    && !owner.equals(p2pManager.getRoomAdminName())
                    && !gameEngine.isGameOver()) {
                logger.logEvent("PeerListener : SECRET_SET ignoré car seul l'admin ("
                        + p2pManager.getRoomAdminName() + ") peut définir le secret.");
                System.out.println("[PeerListener] SECRET_SET bloqué : seul l'admin peut définir le secret pour cette manche.");
                return;
            }

            // Informe P2PManager du détenteur pour le routage de sendGuess()
            p2pManager.setCurrentSecretOwner(owner);

            if (isRandom) {
                // Cas 1 : secret aléatoire, l'admin est aussi joueur — ne pas le retirer des tours
                System.out.println("[GAME] Secret défini par " + owner + " (aléatoire, l'admin joue aussi).");
                logger.logEvent("PeerListener : SECRET_SET RANDOM par " + owner + " — admin reste dans l'ordre des tours.");
            } else {
                // Cas 2 : l'admin a choisi le secret — le retirer de l'ordre des tours
                p2pManager.removePlayerFromTurnOrder(owner);
                if (owner.equals(p2pManager.getPlayerName())) {
                    System.out.println("[GAME] Vous êtes le détenteur du secret. Attendez les propositions.");
                } else {
                    System.out.println("[GAME] Secret défini par " + owner + ". À vous de deviner !");
                }
            }

            logger.logEvent("Détenteur du secret : " + owner
                    + (owner.equals(p2pManager.getPlayerName()) ? " (ce client)" : "")
                    + (isRandom ? " [RANDOM — admin joueur]" : ""));

        } catch (ParseException e) {
            logger.logError("PeerListener : SECRET_SET mal formé.", e);
        }
    }

    /**
     * GG|GUESS|c1|c2|c3|c4
     * Reçu par tous les pairs — seul le détenteur du secret calcule et renvoie le feedback.
     */
    private void onGuess(Message msg) {
        String guesser = peerName;
        if (guesser == null) {
            guesser = p2pManager.getPeerNameBySocket(peerSocket);
            if (guesser != null) {
                peerName = guesser;
            }
        }
        if (guesser == null) {
            guesser = "inconnu";
        }

        // Vérifier si c'est le tour du guesser
        if (!p2pManager.isPlayerTurn(guesser)) {
            logger.logEvent("PeerListener : GUESS de " + guesser + " ignoré car ce n'est pas son tour.");
            return;
        }

        if (gameEngine.isGameOver()) {
            logger.logEvent("PeerListener : GUESS reçu mais la manche est terminée, message ignoré.");
            return;
        }
        if (!gameEngine.isSecretOwner()) {
            logger.logEvent("PeerListener : GUESS reçu mais ce client n'est pas le détenteur.");
            return;
        }
        try {
            if (msg.getFields().size() < GameEngine.COMBINATION_SIZE) {
                logger.logError("PeerListener : GUESS mal formé (champ manquant).");
                return;
            }

            boolean lastAttempt = false;
            if (msg.getFields().size() > GameEngine.COMBINATION_SIZE) {
                String marker = msg.getField(GameEngine.COMBINATION_SIZE);
                lastAttempt = "LAST".equalsIgnoreCase(marker);
            }

            List<Color> guess = new ArrayList<>();
            for (int i = 0; i < GameEngine.COMBINATION_SIZE; i++) {
                guess.add(Color.fromString(msg.getField(i)));
            }

            Feedback feedback = gameEngine.checkGuess(guess, guesser);

            logger.logEvent("Feedback pour " + guesser + " : " + feedback);
            p2pManager.sendFeedback(guesser, feedback);

            // Si pas de victoire, gérer le dernier essai ou passer au tour suivant.
            if (!feedback.isWin()) {
                if (lastAttempt) {
                    p2pManager.decrementActivePlayers();
                    p2pManager.broadcast(MessageParser.serialize(CommandType.PLAYER_OUT, guesser));
                    p2pManager.removePlayerFromTurnOrder(guesser);
                    logger.logEvent("PeerListener : " + guesser + " est éliminé après son dernier essai.");

                    if (p2pManager.getTurnOrderSize() == 0) {
                        p2pManager.broadcast(MessageParser.serialize(CommandType.GAME_OVER, "LOSE", "NONE"));
                        gameEngine.setGameOver(true);
                        logger.logEvent("PeerListener : fin de partie, plus aucun joueur actif après le dernier guess de " + guesser + ".");
                        return;
                    }
                    // removePlayerFromTurnOrder (Option A) pointe déjà sur le joueur suivant — pas de nextTurn()
                } else {
                    p2pManager.nextTurn();
                }
                String nextPlayer = p2pManager.getCurrentTurnPlayer();
                if (nextPlayer != null) {
                    p2pManager.broadcast(MessageParser.serialize(CommandType.NEXT_TURN, nextPlayer));
                }
            }

        } catch (ParseException e) {
            logger.logError("PeerListener : GUESS mal formé.", e);
        }
    }

    /**
     * GG|FEEDBACK|couleurs_correctes|positions_correctes
     */
    private void onFeedback(Message msg) {        // Le détenteur du secret ne doit pas voir le feedback des autres joueurs
        if (gameEngine.isSecretOwner()) {
            logger.logEvent("PeerListener : FEEDBACK ignoré (ce client est le détenteur du secret).");
            return;
        }        try {
            msg.requireFields(2);
            int correctColors    = Integer.parseInt(msg.getField(0));
            int correctPositions = Integer.parseInt(msg.getField(1));
            Feedback feedback    = new Feedback(correctColors, correctPositions);
            logger.logEvent("Feedback reçu → Couleurs OK : " + correctColors
                    + " | Positions OK : " + correctPositions
                    + (feedback.isWin() ? " ★ VICTOIRE !" : ""));
            if (feedback.isWin()) {
                gameEngine.setGameOver(true);
                logger.logEvent("PeerListener : marque la manche comme terminée (WINNER)." );
            }
        } catch (ParseException | NumberFormatException e) {
            logger.logError("PeerListener : FEEDBACK mal formé.", e);
        }
    }

    /**
     * GG|WINNER|nom_joueur
     */
    private void onWinner(Message msg) {
        try {
            msg.requireFields(1);
            String winner = msg.getField(0);
            String consoleMsg = "★★★ GAGNANT : " + winner + " ★★★";
            logger.logEvent(consoleMsg);
            System.out.println("[GAME] " + consoleMsg);
            gameEngine.setGameOver(true);
            p2pManager.broadcast("GG|GAME_OVER|WIN|" + winner);
            logger.logEvent("PeerListener : manche terminée suite à WINNER — GAME_OVER diffusé.");
        } catch (ParseException e) {
            logger.logError("PeerListener : WINNER mal formé.", e);
        }
    }

    /**
     * GG|GAME_OVER|WIN|joueur ou GG|GAME_OVER|LOSE|NONE
     */
    private void onGameOver(Message msg) {
        try {
            List<String> fields = msg.getFields();
            String result = (!fields.isEmpty()) ? fields.get(0) : "UNKNOWN";
            String winner = (fields.size() > 1) ? fields.get(1) : "NONE";

            if ("WIN".equalsIgnoreCase(result)) {
                // La victoire a déjà été annoncée par WINNER — pas de doublon
            } else if ("LOSE".equalsIgnoreCase(result)) {
                System.out.println("[GAME] Partie terminée — personne n'a trouvé le secret.");
            } else {
                System.out.println("[GAME] Partie terminée.");
            }

            gameEngine.setGameOver(true);
            // Indiquer les actions disponibles selon le rôle
            String adminAfterOver = p2pManager.getRoomAdminName();
            if (adminAfterOver != null && adminAfterOver.equals(p2pManager.getPlayerName())) {
                System.out.println("[GAME] Tapez 'newgame' pour lancer une nouvelle manche, ou 'leave <salle>' pour quitter.");
            } else {
                System.out.println("[GAME] En attente de la prochaine manche (l'admin peut taper 'newgame'), ou tapez 'leave <salle>' pour quitter.");
            }
            logger.logEvent("PeerListener : manche terminée (GAME_OVER). result=" + result + " winner=" + winner);

            // Notifie le serveur que la partie est bien terminée (pour libérer la room)
            p2pManager.notifyServerGameOver(result, winner);

        } catch (Exception e) {
            logger.logError("PeerListener : GAME_OVER mal formé.", e);
        }
    }

    /**
     * GG|NEW_GAME — réinitialise le GameEngine local.
     */
    private void onNewGame() {
        p2pManager.resetForNewGame();
        System.out.println("[GAME] Nouvelle manche ! Les connexions ont été réinitialisées.");
        String adminNewGame = p2pManager.getRoomAdminName();
        if (adminNewGame != null && !adminNewGame.equals(p2pManager.getPlayerName())) {
            System.out.println("[GAME] En attente que " + adminNewGame + " définisse le secret...");
        }
        logger.logEvent("PeerListener : nouvelle manche — état local réinitialisé.");
    }

    /**
     * GG|NEXT_TURN|nom_joueur
     */
    private void onNextTurn(Message msg) {
        try {
            msg.requireFields(1);
            String nextPlayer = msg.getField(0);
            p2pManager.setCurrentTurnPlayer(nextPlayer);
            logger.logEvent("PeerListener : tour passé à " + nextPlayer);
        } catch (ParseException e) {
            logger.logError("PeerListener : NEXT_TURN mal formé.", e);
        }
    }

    /**
     * GG|TURN_ANNOUNCEMENT|nom_joueur
     */
    private void onTurnAnnouncement(Message msg) {
        try {
            msg.requireFields(1);
            String currentPlayer = msg.getField(0);
            // Synchronise l'état local avec l'annonce de l'admin (source de vérité)
            p2pManager.setCurrentTurnPlayer(currentPlayer);
            System.out.println("[GAME] C'est le tour de " + currentPlayer + " pour deviner.");
            logger.logEvent("PeerListener : annonce du tour pour " + currentPlayer);
        } catch (ParseException e) {
            logger.logError("PeerListener : TURN_ANNOUNCEMENT mal formé.", e);
        }
    }

    /**
     * GG|PLAYER_OUT|nom_joueur
     */
    private void onPlayerOut(Message msg) {
        try {
            msg.requireFields(1);
            String outPlayer = msg.getField(0);
            p2pManager.decrementActivePlayers();
            p2pManager.removePlayerFromTurnOrder(outPlayer);
            System.out.println("[GAME] " + outPlayer + " a épuisé ses tentatives.");
            logger.logEvent("PeerListener : " + outPlayer + " est out, joueurs actifs restants : " + p2pManager.getActivePlayersCount());
            // Si le détenteur du secret et plus aucun joueur actif, finir la partie
            if (gameEngine.isSecretOwner() && p2pManager.getActivePlayersCount() == 0) {
                p2pManager.broadcast(MessageParser.serialize(CommandType.GAME_OVER, "LOSE", "NONE"));
                gameEngine.setGameOver(true);
                System.out.println("[PeerListener] Partie terminée : tous les joueurs ont épuisé leurs tentatives.");
            }
        } catch (ParseException e) {
            logger.logError("PeerListener : PLAYER_OUT mal formé.", e);
        }
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    private void closeSilently() {
        try {
            if (!peerSocket.isClosed()) {
                peerSocket.close();
            }
        } catch (IOException ignored) {}
    }

    public String getPeerName() { return peerName; }
}
