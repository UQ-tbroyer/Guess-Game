package server;

/**
 * Représente l'état du cycle de vie d'une session de jeu (GameSession).
 *
 * Transitions valides :
 *   WAITING → IN_PROGRESS → WON
 *                         → LOST
 *                         → ABORTED
 *
 * Hypothèse : une session passe à ABORTED si un joueur quitte la salle
 * en cours de partie ou si le serveur force l'arrêt.
 */
public enum SessionStatus {

    /**
     * La session a été créée mais la combinaison secrète n'a pas encore
     * été définie (en attente du SECRET_SET).
     */
    WAITING,

    /**
     * La combinaison secrète est définie, des propositions sont en cours.
     */
    IN_PROGRESS,

    /**
     * Un joueur a deviné la combinaison exacte (WINNER reçu).
     */
    WON,

    /**
     * Le nombre maximum de tentatives a été atteint sans trouver la combinaison.
     */
    LOST,

    /**
     * La partie a été interrompue (départ d'un joueur, kick, arrêt serveur, etc.).
     */
    ABORTED;

    /**
     * Indique si la session est terminée (ne peut plus accepter de GUESS).
     *
     * @return true si la session est dans un état terminal
     */
    public boolean isTerminal() {
        return this == WON || this == LOST || this == ABORTED;
    }
}