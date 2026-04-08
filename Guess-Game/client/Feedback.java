package client;

/**
 * Feedback — délègue à {@link common.Feedback} pour la cohérence client/serveur.
 *
 * Cette classe est conservée pour la compatibilité des importations existantes
 * dans le package client. Tous les calculs sont délégués à la version commune.
 */
public final class Feedback extends common.Feedback {

    public Feedback(int correctColors, int correctPositions) {
        super(correctColors, correctPositions);
    }
}
