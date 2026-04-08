package common;

/**
 * Feedback — résultat immuable d'une proposition de devinette.
 *
 * Partagé entre client et serveur pour garantir la cohérence du protocole.
 *
 * Hypothèse : une victoire = correctPositions == COMBINATION_SIZE,
 * peu importe la valeur de correctColors.
 */
public class Feedback {

    /** Taille de la combinaison secrète — doit correspondre à GameEngine.COMBINATION_SIZE. */
    public static final int COMBINATION_SIZE = 4;

    /** Nombre de couleurs présentes dans le secret mais à la mauvaise position. */
    private final int correctColors;

    /** Nombre de couleurs à la bonne position. */
    private final int correctPositions;

    /**
     * @param correctColors    couleurs présentes dans le secret mais mal placées
     * @param correctPositions couleurs exactement bien placées
     */
    public Feedback(int correctColors, int correctPositions) {
        this.correctColors    = correctColors;
        this.correctPositions = correctPositions;
    }

    /**
     * Indique si la proposition est une victoire (toutes les positions correctes).
     *
     * @return true si le joueur a deviné la combinaison exacte
     */
    public boolean isWin() {
        return correctPositions == COMBINATION_SIZE;
    }

    /**
     * Formate le feedback en message protocole GG complet, prêt à envoyer sur le réseau.
     * Exemple : "GG|FEEDBACK|2|1"
     *
     * @return chaîne réseau complète
     */
    public String toGGString() {
        return "GG|FEEDBACK|" + correctColors + "|" + correctPositions;
    }

    /**
     * Formate uniquement les données du feedback, sans le préfixe GG.
     * Exemple : "2|1"
     * Utile pour l'intégration dans des messages construits manuellement.
     *
     * @return chaîne de données
     */
    public String toDataString() {
        return correctColors + "|" + correctPositions;
    }

    public int getCorrectColors()    { return correctColors; }
    public int getCorrectPositions() { return correctPositions; }

    @Override
    public String toString() {
        return "Feedback{correctColors=" + correctColors
             + ", correctPositions=" + correctPositions
             + ", isWin=" + isWin() + "}";
    }
}
