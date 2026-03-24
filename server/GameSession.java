package server;

import common.Color;
import common.DebugLogger;
import common.ParseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Représente une session de jeu unique dans une salle ou contre le serveur.
 *
 * Responsabilités :
 *  - Stocker la combinaison secrète.
 *  - Valider et évaluer les propositions (GUESS).
 *  - Suivre le nombre de tentatives restantes.
 *  - Enregistrer l'historique des propositions (GuessRecord).
 *  - Gérer le cycle de vie via SessionStatus.
 *
 * Hypothèses :
 *  - Une session est créée par Room.startNewSession() ou par le serveur pour PLAY_SERVER.
 *  - La combinaison secrète est composée exactement de 4 couleurs (COMBO_SIZE).
 *  - Pour PLAY_SERVER, le serveur génère la combinaison aléatoirement via generateSecret().
 *  - Pour une partie P2P, secretOwner est le nom du joueur qui détient le secret
 *    (le serveur ne connaît pas la combinaison ; secretCombination reste null).
 *  - Cette classe n'est pas thread-safe en elle-même : la synchronisation est
 *    assurée par Room ou par ClientHandler qui l'appelle dans un bloc synchronized.
 */
public class GameSession {

    /** Nombre de couleurs dans une combinaison. */
    public static final int COMBO_SIZE = 4;

    /** Toutes les couleurs disponibles pour la génération aléatoire. */
    private static final List<Color> ALL_COLORS =
            Collections.unmodifiableList(Arrays.asList(Color.values()));

    // -------------------------------------------------------------------------
    // État
    // -------------------------------------------------------------------------

    /** Identifiant unique de la session (pour les logs). */
    private final UUID sessionId;

    /** Nom de la salle à laquelle appartient cette session. */
    private final String roomName;

    /**
     * Combinaison secrète.
     * Non-null uniquement pour les parties contre le serveur (PLAY_SERVER).
     * Dans une partie P2P, le secret est détenu par un client.
     */
    private List<Color> secretCombination;

    /**
     * Nom du joueur qui détient le secret.
     * Pour PLAY_SERVER : valeur conventionnelle "SERVER".
     */
    private String secretOwner;

    /** Nombre de tentatives restantes. */
    private int attemptsLeft;

    /** Nombre maximum de tentatives (conservé pour les logs et le rapport). */
    private final int maxAttempts;

    /** Historique ordonné des propositions. */
    private final List<GuessRecord> guessLog;

    /** Statut courant de la session. */
    private SessionStatus status;

    /** Horodatage de création de la session. */
    private final Instant createdAt;

    /** Horodatage de fin de session (null tant que non terminée). */
    private Instant endedAt;

    // -------------------------------------------------------------------------
    // Constructeur
    // -------------------------------------------------------------------------

    /**
     * Crée une nouvelle session dans l'état WAITING.
     *
     * @param roomName    nom de la salle (ou "SERVER" pour PLAY_SERVER)
     * @param maxAttempts nombre maximal de tentatives autorisées
     */
    public GameSession(String roomName, int maxAttempts) {
        this.sessionId   = UUID.randomUUID();
        this.roomName    = roomName;
        this.maxAttempts = maxAttempts;
        this.attemptsLeft = maxAttempts;
        this.guessLog    = new ArrayList<>();
        this.status      = SessionStatus.WAITING;
        this.createdAt   = Instant.now();

        DebugLogger.getInstance().logEvent(
                "Session créée [" + sessionId + "] salle=" + roomName +
                        " maxTentatives=" + maxAttempts
        );
    }

    // -------------------------------------------------------------------------
    // Génération / définition du secret
    // -------------------------------------------------------------------------

    /**
     * Génère une combinaison secrète aléatoire de {@value #COMBO_SIZE} couleurs.
     * Utilisé pour les parties PLAY_SERVER.
     * Plusieurs couleurs identiques sont autorisées (hypothèse : pas de contrainte d'unicité).
     *
     * Passe le statut de WAITING → IN_PROGRESS.
     *
     * @throws IllegalStateException si la session n'est pas dans l'état WAITING
     */
    public void generateSecret() {
        requireStatus(SessionStatus.WAITING);

        List<Color> combo = new ArrayList<>(COMBO_SIZE);
        List<Color> pool  = new ArrayList<>(ALL_COLORS);
        Collections.shuffle(pool);
        for (int i = 0; i < COMBO_SIZE; i++) {
            combo.add(pool.get(i % pool.size()));
        }
        this.secretCombination = Collections.unmodifiableList(combo);
        this.secretOwner = "SERVER";
        this.status = SessionStatus.IN_PROGRESS;

        DebugLogger.getInstance().logEvent(
                "Session [" + sessionId + "] : secret généré par SERVER, statut → IN_PROGRESS"
        );
    }

    /**
     * Définit le secret pour une partie P2P (secretCombination reste null côté serveur).
     * Seul le propriétaire du secret est enregistré.
     * Passe le statut de WAITING → IN_PROGRESS.
     *
     * @param ownerName nom du joueur qui détient le secret
     * @throws IllegalStateException si la session n'est pas dans l'état WAITING
     */
    public void setSecretOwner(String ownerName) {
        requireStatus(SessionStatus.WAITING);
        this.secretOwner = ownerName;
        this.status = SessionStatus.IN_PROGRESS;

        DebugLogger.getInstance().logEvent(
                "Session [" + sessionId + "] : secret défini par " + ownerName +
                        ", statut → IN_PROGRESS"
        );
    }

    // -------------------------------------------------------------------------
    // Évaluation des propositions
    // -------------------------------------------------------------------------

    /**
     * Évalue une proposition de {@value #COMBO_SIZE} couleurs contre la combinaison secrète.
     * Utilisé uniquement pour les parties PLAY_SERVER (secretCombination != null).
     *
     * Algorithme Mastermind :
     *  1. Compter les positions correctes (couleur ET position).
     *  2. Compter les couleurs correctes (présentes mais mal placées) :
     *     pour chaque couleur, min(occurrences dans guess, occurrences dans secret).
     *     Le résultat inclut les positions correctes.
     *
     * Met à jour attemptsLeft et change le statut si nécessaire.
     *
     * @param guess liste de {@value #COMBO_SIZE} couleurs proposée par le joueur
     * @param playerName nom du joueur qui propose
     * @return un objet Feedback avec correctColors et correctPositions
     * @throws IllegalStateException si la session n'est pas IN_PROGRESS
     * @throws IllegalArgumentException si guess est null ou de mauvaise taille
     */
    public Feedback checkGuess(List<Color> guess, String playerName) {
        requireStatus(SessionStatus.IN_PROGRESS);

        if (guess == null || guess.size() != COMBO_SIZE) {
            throw new IllegalArgumentException(
                    "Une proposition doit contenir exactement " + COMBO_SIZE + " couleurs."
            );
        }
        if (secretCombination == null) {
            throw new IllegalStateException(
                    "checkGuess() ne peut être appelé que pour les parties PLAY_SERVER " +
                            "(secretCombination est null pour les parties P2P)."
            );
        }

        // --- Calcul des positions correctes ---
        int correctPositions = 0;
        int[] secretCount = new int[Color.values().length];
        int[] guessCount  = new int[Color.values().length];

        for (int i = 0; i < COMBO_SIZE; i++) {
            if (guess.get(i) == secretCombination.get(i)) {
                correctPositions++;
            } else {
                secretCount[secretCombination.get(i).ordinal()]++;
                guessCount[guess.get(i).ordinal()]++;
            }
        }

        // --- Calcul des couleurs correctes (mal placées) ---
        int colorMatches = 0;
        for (int i = 0; i < Color.values().length; i++) {
            colorMatches += Math.min(secretCount[i], guessCount[i]);
        }
        int correctColors = correctPositions + colorMatches;

        // --- Décrément des tentatives ---
        attemptsLeft--;

        // --- Enregistrement dans l'historique ---
        GuessRecord record = new GuessRecord(
                new ArrayList<>(guess), correctColors, correctPositions, playerName
        );
        guessLog.add(record);

        // --- Mise à jour du statut ---
        if (correctPositions == COMBO_SIZE) {
            status  = SessionStatus.WON;
            endedAt = Instant.now();
            DebugLogger.getInstance().logEvent(
                    "Session [" + sessionId + "] : GAGNÉE par " + playerName +
                            " en " + (maxAttempts - attemptsLeft) + " tentative(s)."
            );
        } else if (attemptsLeft <= 0) {
            status  = SessionStatus.LOST;
            endedAt = Instant.now();
            DebugLogger.getInstance().logEvent(
                    "Session [" + sessionId + "] : PERDUE — plus de tentatives."
            );
        }

        DebugLogger.getInstance().logEvent(
                "Session [" + sessionId + "] GUESS de " + playerName +
                        " → couleursCorrectes=" + correctColors +
                        " positionsCorrectes=" + correctPositions +
                        " tentativesRestantes=" + attemptsLeft
        );

        return new Feedback(correctColors, correctPositions);
    }

    // -------------------------------------------------------------------------
    // Fin de session
    // -------------------------------------------------------------------------

    /**
     * Marque la session comme ABORTED (joueur parti, kick, arrêt serveur).
     * Sans effet si la session est déjà dans un état terminal.
     */
    public void abort() {
        if (!status.isTerminal()) {
            status  = SessionStatus.ABORTED;
            endedAt = Instant.now();
            DebugLogger.getInstance().logEvent(
                    "Session [" + sessionId + "] : ABORTED."
            );
        }
    }

    // -------------------------------------------------------------------------
    // Accesseurs
    // -------------------------------------------------------------------------

    public UUID getSessionId()               { return sessionId; }
    public String getRoomName()              { return roomName; }
    public String getSecretOwner()           { return secretOwner; }
    public int getAttemptsLeft()             { return attemptsLeft; }
    public int getMaxAttempts()              { return maxAttempts; }
    public SessionStatus getStatus()         { return status; }
    public Instant getCreatedAt()            { return createdAt; }
    public Instant getEndedAt()              { return endedAt; }
    public List<GuessRecord> getGuessLog()   { return Collections.unmodifiableList(guessLog); }

    /**
     * @return true si la session est terminée (WON, LOST ou ABORTED)
     */
    public boolean isFinished() {
        return status.isTerminal();
    }

    /**
     * @return la combinaison secrète, ou null pour une partie P2P
     */
    public List<Color> getSecretCombination() {
        return secretCombination;
    }

    // -------------------------------------------------------------------------
    // Utilitaires internes
    // -------------------------------------------------------------------------

    /**
     * Vérifie que la session est dans le statut attendu.
     *
     * @param expected statut requis
     * @throws IllegalStateException si le statut courant diffère
     */
    private void requireStatus(SessionStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    "Opération invalide : statut courant=" + status +
                            ", requis=" + expected + " [session=" + sessionId + "]"
            );
        }
    }

    // -------------------------------------------------------------------------
    // Classe interne : GuessRecord
    // -------------------------------------------------------------------------

    /**
     * Enregistrement immuable d'une proposition.
     * Stocké dans guessLog pour le rapport et les logs.
     */
    public static final class GuessRecord {

        private final List<Color> guess;
        private final int correctColors;
        private final int correctPositions;
        private final String playerName;
        private final Instant timestamp;

        public GuessRecord(List<Color> guess, int correctColors,
                           int correctPositions, String playerName) {
            this.guess             = Collections.unmodifiableList(new ArrayList<>(guess));
            this.correctColors     = correctColors;
            this.correctPositions  = correctPositions;
            this.playerName        = playerName;
            this.timestamp         = Instant.now();
        }

        public List<Color>  getGuess()             { return guess; }
        public int          getCorrectColors()      { return correctColors; }
        public int          getCorrectPositions()   { return correctPositions; }
        public String       getPlayerName()         { return playerName; }
        public Instant      getTimestamp()          { return timestamp; }

        @Override
        public String toString() {
            return playerName + " → " + guess +
                    " [couleurs=" + correctColors +
                    ", positions=" + correctPositions + "]";
        }
    }

    // -------------------------------------------------------------------------
    // Classe interne : Feedback
    // -------------------------------------------------------------------------

    /**
     * Résultat retourné par checkGuess().
     * Correspond au message GG|FEEDBACK|couleurs_correctes|positions_correctes.
     */
    public static final class Feedback {

        private final int correctColors;
        private final int correctPositions;

        public Feedback(int correctColors, int correctPositions) {
            this.correctColors    = correctColors;
            this.correctPositions = correctPositions;
        }

        public int  getCorrectColors()    { return correctColors; }
        public int  getCorrectPositions() { return correctPositions; }

        /**
         * @return true si la proposition est exacte (partie gagnée)
         */
        public boolean isWin() {
            return correctPositions == COMBO_SIZE;
        }

        /**
         * Sérialise le feedback au format réseau GG.
         * Exemple : "3|1"  (utilisé dans GG|FEEDBACK|3|1)
         *
         * @return chaîne "correctColors|correctPositions"
         */
        public String toGGString() {
            return correctColors + "|" + correctPositions;
        }

        @Override
        public String toString() {
            return "Feedback{couleurs=" + correctColors +
                    ", positions=" + correctPositions + "}";
        }
    }
}