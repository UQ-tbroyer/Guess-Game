package client;

import common.Color;
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
                handleRawLine(rawLine.trim());
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
            case SECRET_SET -> onSecretSet(msg);
            case GUESS      -> onGuess(msg);
            case FEEDBACK   -> onFeedback(msg);
            case WINNER     -> onWinner(msg);
            case GAME_OVER  -> onGameOver(msg);
            case NEW_GAME   -> onNewGame(msg);
            default         -> logger.logError(
                    "PeerListener : commande P2P inattendue : " + msg.getCommand());
        }
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /**
     * GG|SECRET_SET|nom_joueur
     * Identifie le détenteur du secret et met à jour P2PManager.
     */
    private void onSecretSet(Message msg) {
        try {
            msg.requireFields(1);
            String owner = msg.getField(0);

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

            // Informe P2PManager du détenteur pour le routage de sendGuess()
            p2pManager.setCurrentSecretOwner(owner);

            if (owner.equals(p2pManager.getPlayerName())) {
                System.out.println("[PeerListener] Secret défini : vous êtes le détenteur du secret.");
            } else {
                System.out.println("[PeerListener] Secret défini par " + owner + ". Vous devez deviner.");
            }

            logger.logEvent("Détenteur du secret : " + owner
                    + (owner.equals(p2pManager.getPlayerName()) ? " (ce client)" : ""));

        } catch (ParseException e) {
            logger.logError("PeerListener : SECRET_SET mal formé.", e);
        }
    }

    /**
     * GG|GUESS|c1|c2|c3|c4
     * Reçu par le détenteur du secret — calcule et renvoie le feedback.
     */
    private void onGuess(Message msg) {
        if (gameEngine.isGameOver()) {
            logger.logEvent("PeerListener : GUESS reçu mais la manche est terminée, message ignoré.");
            return;
        }
        if (!gameEngine.isSecretOwner()) {
            logger.logError("PeerListener : GUESS reçu mais ce client n'est pas le détenteur.");
            return;
        }
        try {
            msg.requireFields(GameEngine.COMBINATION_SIZE);

            List<Color> guess = new ArrayList<>();
            for (int i = 0; i < GameEngine.COMBINATION_SIZE; i++) {
                guess.add(Color.fromString(msg.getField(i)));
            }

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

            Feedback feedback = gameEngine.checkGuess(guess, guesser);

            logger.logEvent("Feedback pour " + guesser + " : " + feedback);
            p2pManager.sendFeedback(guesser, feedback);

        } catch (ParseException e) {
            logger.logError("PeerListener : GUESS mal formé.", e);
        }
    }

    /**
     * GG|FEEDBACK|couleurs_correctes|positions_correctes
     */
    private void onFeedback(Message msg) {
        try {
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
            logger.logEvent("PeerListener : manche terminée suite à WINNER.");
        } catch (ParseException e) {
            logger.logError("PeerListener : WINNER mal formé.", e);
        }
    }

    /**
     * GG|GAME_OVER|WIN|joueur ou GG|GAME_OVER|LOSE|NONE
     */
    private void onGameOver(Message msg) {
        try {
            String[] fields = msg.getFields().toArray(new String[0]);
            String result = (fields.length > 0) ? fields[0] : "UNKNOWN";
            String winner = (fields.length > 1) ? fields[1] : "NONE";

            if ("WIN".equalsIgnoreCase(result)) {
                System.out.println("[PeerListener] Partie terminée : victoire de " + winner + " !");
            } else if ("LOSE".equalsIgnoreCase(result)) {
                System.out.println("[PeerListener] Partie terminée : défaite (pas de gagnant)." );
            } else {
                System.out.println("[PeerListener] Partie terminée : résultat inconnu.");
            }

            gameEngine.setGameOver(true);
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
    private void onNewGame(Message msg) {
        gameEngine.reset();
        logger.logEvent("PeerListener : nouvelle manche — GameEngine réinitialisé.");
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    private void closeSilently() {
        try { if (!peerSocket.isClosed()) peerSocket.close(); }
        catch (IOException ignored) {}
    }

    public String getPeerName() { return peerName; }
}
