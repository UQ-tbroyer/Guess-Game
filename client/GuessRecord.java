package client;

import common.Color;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GuessRecord — enregistrement immuable d'une proposition de devinette.
 *
 * Stocké dans l'historique de GameEngine pour permettre d'afficher
 * le déroulement complet de la partie lors de la démo.
 *
 * Format de trace conforme aux directives (chaque champ sur une ligne distincte).
 */
public final class GuessRecord {

    // -------------------------------------------------------------------------
    // Attributs
    // -------------------------------------------------------------------------

    /** Couleurs proposées — copie défensive immuable. */
    private final List<Color> guess;

    /** Nombre de couleurs correctes mais mal placées. */
    private final int correctColors;

    /** Nombre de couleurs correctement placées. */
    private final int correctPositions;

    /** Nom du joueur qui a soumis cette proposition. */
    private final String playerName;

    /** Horodatage de la réception de la proposition. */
    private final Instant timestamp;

    // -------------------------------------------------------------------------
    // Constructeur
    // -------------------------------------------------------------------------

    /**
     * @param guess            couleurs de la proposition
     * @param correctColors    nombre de couleurs correctes mal placées
     * @param correctPositions nombre de couleurs bien placées
     * @param playerName       nom du joueur
     */
    public GuessRecord(List<Color> guess, int correctColors,
                       int correctPositions, String playerName) {
        this.guess             = Collections.unmodifiableList(new ArrayList<>(guess));
        this.correctColors     = correctColors;
        this.correctPositions  = correctPositions;
        this.playerName        = playerName;
        this.timestamp         = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Affichage traces (conforme aux directives de l'énoncé)
    // -------------------------------------------------------------------------

    /**
     * Retourne une représentation textuelle de la proposition,
     * avec chaque champ sur une ligne distincte.
     *
     * Exemple de sortie :
     *   Joueur        : Alice
     *   Couleur 1     : RED
     *   Couleur 2     : BLUE
     *   Couleur 3     : GREEN
     *   Couleur 4     : YELLOW
     *   Couleurs OK   : 2
     *   Positions OK  : 1
     *   Horodatage    : 2026-03-23T14:05:32.123Z
     */
    public String toTraceString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Joueur        : ").append(playerName).append("\n");
        for (int i = 0; i < guess.size(); i++) {
            sb.append(String.format("Couleur %-5d : %s%n", i + 1, guess.get(i).name()));
        }
        sb.append("Couleurs OK   : ").append(correctColors).append("\n");
        sb.append("Positions OK  : ").append(correctPositions).append("\n");
        sb.append("Horodatage    : ").append(timestamp);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public List<Color> getGuess()        { return guess; }
    public int getCorrectColors()        { return correctColors; }
    public int getCorrectPositions()     { return correctPositions; }
    public String getPlayerName()        { return playerName; }
    public Instant getTimestamp()        { return timestamp; }

    @Override
    public String toString() {
        return "GuessRecord{player=" + playerName
             + ", guess=" + guess
             + ", correctColors=" + correctColors
             + ", correctPositions=" + correctPositions + "}";
    }
}
