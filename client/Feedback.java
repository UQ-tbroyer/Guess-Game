package client;

/**
 * Feedback — résultat immuable d'une proposition de devinette.
 *
 * Produit par GameEngine.checkGuess() et sérialisé en message
 * GG|FEEDBACK|correctColors|correctPositions avant envoi au pair.
 *
 * Hypothèse : une victoire = correctPositions == GameEngine.COMBINATION_SIZE,
 * peu importe la valeur de correctColors.
 */
public final class Feedback {

    // -------------------------------------------------------------------------
    // Attributs
    // -------------------------------------------------------------------------

    /** Nombre de couleurs présentes dans le secret mais à la mauvaise position. */
    private final int correctColors;

    /** Nombre de couleurs à la bonne position. */
    private final int correctPositions;

    // -------------------------------------------------------------------------
    // Constructeur
    // -------------------------------------------------------------------------

    /**
     * @param correctColors    couleurs présentes dans le secret mais mal placées
     * @param correctPositions couleurs exactement bien placées
     */
    public Feedback(int correctColors, int correctPositions) {
        this.correctColors    = correctColors;
        this.correctPositions = correctPositions;
    }

    // -------------------------------------------------------------------------
    // Logique
    // -------------------------------------------------------------------------

    /**
     * Indique si la proposition est une victoire
     * (toutes les positions sont correctes).
     *
     * @return true si le joueur a deviné la combinaison exacte
     */
    public boolean isWin() {
        return correctPositions == GameEngine.COMBINATION_SIZE;
    }

    /**
     * Formate le feedback en message protocole GG prêt à envoyer.
     * Exemple : "GG|FEEDBACK|2|1"
     *
     * @return chaîne réseau
     */
    public String toGGString() {
        return "GG|FEEDBACK|" + correctColors + "|" + correctPositions;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public int getCorrectColors()    { return correctColors; }
    public int getCorrectPositions() { return correctPositions; }

    @Override
    public String toString() {
        return "Feedback{correctColors=" + correctColors
             + ", correctPositions=" + correctPositions
             + ", isWin=" + isWin() + "}";
    }
}
