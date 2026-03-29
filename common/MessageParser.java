package common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Classe utilitaire statique responsable du parsing et de la sérialisation
 * des messages du protocole GG.
 *
 * Format réseau : GG|COMMAND|champ1|champ2|...
 *
 * Hypothèses :
 *  - Chaque message est une ligne unique terminée par \n (géré par BufferedReader.readLine).
 *  - Le préfixe "GG" est obligatoire.
 *  - Les champs vides entre délimiteurs sont rejetés.
 *  - Cette classe est thread-safe car elle ne contient aucun état mutable.
 */
public final class MessageParser {

    /** Nombre minimal de tokens dans un message valide : "GG" + commande = 2. */
    private static final int MIN_TOKEN_COUNT = 2;

    /** Constructeur privé : classe 100% statique, non instanciable. */
    private MessageParser() {}

    // =========================================================================
    // Parsing
    // =========================================================================

    /**
     * Parse une ligne brute du protocole GG en objet {@link Message}.
     *
     * Étapes de validation :
     *  1. La ligne ne doit pas être nulle ou vide.
     *  2. Le premier token doit être "GG".
     *  3. Le deuxième token doit correspondre à un {@link CommandType} connu.
     *  4. Les champs restants sont collectés tels quels.
     *
     * @param raw la ligne brute lue sur le réseau
     * @return un Message valide
     * @throws ParseException si la ligne est mal formée
     */
    public static Message parse(String raw) throws ParseException {
        if (raw == null || raw.isBlank()) {
            throw new ParseException("Message nul ou vide reçu.");
        }

        String trimmed = raw.trim();
        String[] tokens = trimmed.split(Message.DELIMITER_REGEX, -1);

        if (tokens.length < MIN_TOKEN_COUNT) {
            throw new ParseException(
                    "Message trop court (moins de 2 tokens) : '" + trimmed + "'."
            );
        }

        // Validation du préfixe GG
        if (!Message.PROTOCOL_PREFIX.equals(tokens[0])) {
            throw new ParseException(
                    "Préfixe invalide : '" + tokens[0] + "'. Attendu : '" + Message.PROTOCOL_PREFIX + "'."
            );
        }

        // Validation de la commande
        CommandType command = CommandType.fromString(tokens[1]);

        // Collecte des champs (tout ce qui suit la commande)
        List<String> fields = new ArrayList<>();
        for (int i = 2; i < tokens.length; i++) {
            // On rejette les champs vides produits par des || consécutifs
            if (tokens[i].isEmpty()) {
                throw new ParseException(
                        "Champ vide détecté à la position " + (i - 2) + " dans : '" + trimmed + "'."
                );
            }
            fields.add(tokens[i]);
        }

        return new Message(command, fields, trimmed);
    }

    // =========================================================================
    // Sérialisation
    // =========================================================================

    /**
     * Sérialise un objet {@link Message} en ligne réseau.
     * Délègue à {@link Message#toString()}.
     *
     * @param msg le message à sérialiser
     * @return la ligne prête à envoyer (sans \n final)
     * @throws IllegalArgumentException si msg est null
     */
    public static String serialize(Message msg) {
        if (msg == null) {
            throw new IllegalArgumentException("Impossible de sérialiser un message null.");
        }
        return msg.toString();
    }

    /**
     * Construit et sérialise directement un message GG à partir de ses composants.
     * Raccourci pour éviter de créer un Message intermédiaire.
     *
     * Exemple : serialize(CommandType.CONNECT, "Alice")
     *           → "GG|CONNECT|Alice"
     *
     * @param command le type de commande
     * @param fields  les champs (0 ou plusieurs)
     * @return la ligne réseau correspondante
     */
    public static String serialize(CommandType command, String... fields) {
        if (command == null) {
            throw new IllegalArgumentException("La commande ne peut pas être null.");
        }
        List<String> fieldList = (fields != null) ? Arrays.asList(fields) : new ArrayList<>();
        Message msg = new Message(command, fieldList, "");
        return msg.toString();
    }

    // =========================================================================
    // Validation
    // =========================================================================

    /**
     * Valide une ligne réseau sans lever d'exception.
     * Utile pour les tests ou les gardes défensives.
     *
     * @param raw la ligne à valider
     * @return true si la ligne est un message GG valide, false sinon
     */
    public static boolean validate(String raw) {
        try {
            parse(raw);
            return true;
        } catch (ParseException e) {
            return false;
        }
    }
}