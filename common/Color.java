package common;

/**
 * Représente les couleurs possibles dans le jeu Guess Game.
 * Utilisé dans les combinaisons secrètes et les propositions (GUESS).
 *
 * Hypothèse : les noms de couleurs sur le réseau sont les noms d'enum en majuscules (ex: RED, GREEN...).
 */
public enum Color {

    RED,
    GREEN,
    BLUE,
    YELLOW,
    ORANGE;

    /**
     * Convertit une chaîne (insensible à la casse) en Color.
     *
     * @param s la chaîne à convertir (ex: "RED", "red", "Red")
     * @return la couleur correspondante
     * @throws ParseException si la chaîne ne correspond à aucune couleur valide
     */
    public static Color fromString(String s) throws ParseException {
        if (s == null || s.isBlank()) {
            throw new ParseException("Couleur nulle ou vide.");
        }
        try {
            return Color.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ParseException("Couleur invalide : '" + s + "'. Valeurs acceptées : RED, GREEN, BLUE, YELLOW, ORANGE.");
        }
    }

    /**
     * Retourne le nom réseau de la couleur (majuscules), tel qu'utilisé dans les messages GG.
     */
    @Override
    public String toString() {
        return this.name();
    }
}