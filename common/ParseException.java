package common;

/**
 * Exception levée lorsqu'un message GG est mal formé ou qu'une valeur
 * de champ ne correspond à aucune valeur connue (commande inconnue, couleur invalide, etc.).
 *
 * Hypothèse : toute ParseException interceptée par un handler doit être loguée
 * via DebugLogger et provoquer l'envoi d'un message GG|ERROR au client fautif
 * (côté serveur) ou l'affichage d'un message d'erreur en CLI (côté client).
 */
public class ParseException extends Exception {

    /**
     * @param message description lisible de l'erreur de parsing
     */
    public ParseException(String message) {
        super(message);
    }

    /**
     * @param message description lisible de l'erreur de parsing
     * @param cause   exception d'origine (ex: NumberFormatException)
     */
    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}