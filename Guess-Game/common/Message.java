package common;

import java.util.Collections;
import java.util.List;

/**
 * Représentation immuable et thread-safe d'un message du protocole GG.
 *
 * Format réseau : GG|COMMAND|champ1|champ2|...
 *
 * Un Message est toujours construit par {@link MessageParser#parse(String)}.
 * Il ne peut pas être instancié directement depuis l'extérieur du package common.
 *
 * Hypothèses :
 *  - Le préfixe "GG" est obligatoire et validé par MessageParser.
 *  - Les champs sont indexés à partir de 0 (champ 0 = premier champ après la commande).
 *  - La liste de champs peut être vide si la commande n'en requiert pas (ex: LIST_ROOMS, NEW_GAME).
 *  - rawLine conserve la ligne telle que reçue sur le réseau (utile pour le DebugLogger).
 */
public final class Message {

    /** Préfixe obligatoire de tous les messages du protocole. */
    public static final String PROTOCOL_PREFIX = "GG";

    /** Délimiteur de champs. */
    public static final String DELIMITER = "|";

    /** Regex pour le split (le | doit être échappé). */
    public static final String DELIMITER_REGEX = "\\|";

    private final CommandType command;
    private final List<String> fields;
    private final String rawLine;

    /**
     * Constructeur package-private : seul MessageParser peut créer des instances.
     *
     * @param command  commande parsée
     * @param fields   liste immuable des champs (peut être vide)
     * @param rawLine  ligne brute reçue sur le réseau
     */
    Message(CommandType command, List<String> fields, String rawLine) {
        this.command = command;
        this.fields  = Collections.unmodifiableList(fields);
        this.rawLine = rawLine;
    }

    // -------------------------------------------------------------------------
    // Accesseurs
    // -------------------------------------------------------------------------

    /**
     * @return le type de commande du message
     */
    public CommandType getCommand() {
        return command;
    }

    /**
     * Retourne le champ à l'index donné.
     *
     * @param index index 0-based dans la liste des champs
     * @return la valeur du champ
     * @throws IndexOutOfBoundsException si l'index est hors bornes
     */
    public String getField(int index) {
        return fields.get(index);
    }

    /**
     * @return vue non-modifiable de tous les champs
     */
    public List<String> getFields() {
        return fields;
    }

    /**
     * @return nombre de champs du message
     */
    public int getFieldCount() {
        return fields.size();
    }

    /**
     * @return la ligne brute telle que reçue sur le réseau
     */
    public String getRawLine() {
        return rawLine;
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    /**
     * Vérifie que le message possède au moins {@code count} champs.
     *
     * @param count nombre minimal de champs attendus
     * @throws ParseException si le message en contient moins
     */
    public void requireFields(int count) throws ParseException {
        if (fields.size() < count) {
            throw new ParseException(
                    "La commande " + command + " requiert au moins " + count +
                            " champ(s), mais " + fields.size() + " reçu(s). Message : " + rawLine
            );
        }
    }

    /**
     * Reconstruit la représentation réseau du message.
     * Format : GG|COMMAND|champ1|champ2|...
     *
     * @return la ligne prête à envoyer sur le réseau
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(PROTOCOL_PREFIX)
                .append(DELIMITER)
                .append(command.toString());
        for (String field : fields) {
            sb.append(DELIMITER).append(field);
        }
        return sb.toString();
    }
}