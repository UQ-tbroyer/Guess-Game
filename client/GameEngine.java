package client;

import common.Color;
import common.DebugLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * GameEngine — logique de jeu locale côté client.
 *
 * Responsabilités :
 *  - Stocker la combinaison secrète si ce client est le détenteur du secret.
 *  - Calculer le feedback pour une proposition reçue d'un pair (GUESS).
 *  - Maintenir l'historique des propositions (GuessRecord) pour affichage.
 *  - Se réinitialiser proprement à chaque NEW_GAME.
 *
 * Hypothèses :
 *  - Les couleurs peuvent se répéter dans la combinaison secrète (règles Mastermind classiques).
 *  - Une combinaison valide contient exactement COMBINATION_SIZE (4) couleurs non nulles.
 *  - Le rôle de détenteur du secret est attribué par le créateur de la salle (via P2PManager),
 *    puis tourne à chaque nouvelle manche.
 *  - GameEngine ne connaît pas le réseau — logique pure seulement.
 */
public class GameEngine {

    /** Taille fixe d'une combinaison, définie par le protocole GG. */
    public static final int COMBINATION_SIZE = 4;

    // -------------------------------------------------------------------------
    // Attributs
    // -------------------------------------------------------------------------

    /** Combinaison secrète — null si ce client n'est pas le détenteur. */
    private List<Color> secretCombination;

    /** Nombre de tentatives restantes pour la manche courante. */
    private int attemptsLeft;

    /** Vrai si ce client détient la combinaison secrète cette manche. */
    private boolean isSecretOwner;

    /** Historique de toutes les propositions de la manche courante. */
    private final List<GuessRecord> guessHistory;

    private final DebugLogger logger = DebugLogger.getInstance();

    // -------------------------------------------------------------------------
    // Constructeur
    // -------------------------------------------------------------------------

    public GameEngine() {
        this.secretCombination = null;
        this.attemptsLeft      = 0;
        this.isSecretOwner     = false;
        this.guessHistory      = new ArrayList<>();
    }

    // -------------------------------------------------------------------------
    // Configuration de la manche
    // -------------------------------------------------------------------------

    /**
     * Définit la combinaison secrète. Appelé uniquement par le détenteur du secret.
     *
     * @param combo liste de COMBINATION_SIZE couleurs (doublons permis)
     * @throws IllegalArgumentException si la taille est incorrecte ou la liste nulle
     */
    public void setSecret(List<Color> combo) {
        if (combo == null || combo.size() != COMBINATION_SIZE) {
            throw new IllegalArgumentException(
                "La combinaison doit contenir exactement " + COMBINATION_SIZE + " couleurs."
            );
        }
        for (Color c : combo) {
            if (c == null) throw new IllegalArgumentException("Une couleur dans la combinaison est null.");
        }
        this.secretCombination = new ArrayList<>(combo);
        this.isSecretOwner     = true;
        logger.logEvent("Secret défini par ce client : " + getSecretAsString());
    }

    /**
     * Configure le nombre de tentatives pour cette manche.
     * Appelé lors de la réception de GAME_STARTED ou SERVER_GAME_STARTED.
     *
     * @param maxAttempts nombre maximum de tentatives (doit être > 0)
     */
    public void setMaxAttempts(int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("Le nombre de tentatives doit être positif.");
        }
        this.attemptsLeft = maxAttempts;
        logger.logEvent("Tentatives configurées : " + maxAttempts);
    }

    // -------------------------------------------------------------------------
    // Logique de jeu
    // -------------------------------------------------------------------------

    /**
     * Vérifie si une proposition est valide :
     * exactement COMBINATION_SIZE couleurs, toutes non nulles.
     *
     * @param guess proposition à valider
     * @return true si la proposition est valide
     */
    public boolean isValidGuess(List<Color> guess) {
        if (guess == null || guess.size() != COMBINATION_SIZE) return false;
        for (Color c : guess) {
            if (c == null) return false;
        }
        return true;
    }

    /**
     * Calcule le feedback pour une proposition reçue.
     * Appelé UNIQUEMENT si ce client est le détenteur du secret.
     *
     * Algorithme à deux passes (Mastermind classique) :
     *  Passe 1 — positions correctes : même couleur ET même index.
     *  Passe 2 — couleurs correctes  : couleur présente dans le secret,
     *             mauvaise position, sans compter deux fois les positions déjà correctes.
     *
     * @param guess      proposition reçue via GG|GUESS|...|...|...|...
     * @param playerName nom du joueur qui a proposé (pour l'historique)
     * @return objet Feedback avec correctColors et correctPositions
     * @throws IllegalStateException    si ce client n'est pas le détenteur du secret
     * @throws IllegalArgumentException si la proposition est invalide
     */
    public Feedback checkGuess(List<Color> guess, String playerName) {
        if (!isSecretOwner || secretCombination == null) {
            throw new IllegalStateException(
                "checkGuess() appelé alors que ce client n'est pas le détenteur du secret."
            );
        }
        if (!isValidGuess(guess)) {
            throw new IllegalArgumentException("Proposition invalide : " + guess);
        }

        boolean[] secretUsed = new boolean[COMBINATION_SIZE];
        boolean[] guessUsed  = new boolean[COMBINATION_SIZE];

        int correctPositions = 0;
        int correctColors    = 0;

        // Passe 1 : positions correctes
        for (int i = 0; i < COMBINATION_SIZE; i++) {
            if (guess.get(i) == secretCombination.get(i)) {
                correctPositions++;
                secretUsed[i] = true;
                guessUsed[i]  = true;
            }
        }

        // Passe 2 : couleurs correctes (mauvaise position)
        for (int i = 0; i < COMBINATION_SIZE; i++) {
            if (guessUsed[i]) continue;
            for (int j = 0; j < COMBINATION_SIZE; j++) {
                if (secretUsed[j]) continue;
                if (guess.get(i) == secretCombination.get(j)) {
                    correctColors++;
                    secretUsed[j] = true;
                    break;
                }
            }
        }

        // Calculer le nombre total de couleurs correctes (incluant les bien placées)
int totalCorrectColors = correctColors + correctPositions;

// Enregistrement dans l'historique
GuessRecord record = new GuessRecord(guess, totalCorrectColors, correctPositions, playerName);
guessHistory.add(record);
logger.logEvent("Proposition enregistrée :\n" + record.toTraceString());

// Décrémenter les tentatives
if (attemptsLeft > 0) attemptsLeft--;

return new Feedback(totalCorrectColors, correctPositions);
    }

    // -------------------------------------------------------------------------
    // Réinitialisation
    // -------------------------------------------------------------------------

    /**
     * Réinitialise l'état du jeu pour une nouvelle manche.
     * Appelé à la réception de GG|NEW_GAME.
     */
    public void reset() {
        this.secretCombination = null;
        this.attemptsLeft      = 0;
        this.isSecretOwner     = false;
        this.guessHistory.clear();
        logger.logEvent("GameEngine réinitialisé pour une nouvelle manche.");
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public boolean isSecretOwner()             { return isSecretOwner; }
    public int getAttemptsLeft()               { return attemptsLeft; }

    /** Retourne une copie défensive de l'historique. */
    public List<GuessRecord> getGuessHistory() { return new ArrayList<>(guessHistory); }

    /**
     * Retourne le secret sous forme lisible pour les traces.
     * NE PAS envoyer sur le réseau.
     */
    public String getSecretAsString() {
        if (secretCombination == null) return "[aucun secret défini]";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < secretCombination.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(secretCombination.get(i).name());
        }
        return sb.toString();
    }
}
