package common;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Logger singleton thread-safe pour le protocole GG.
 *
 * Responsabilités :
 *  - Afficher sur stdout chaque message entrant/sortant avec ses champs sur des lignes distinctes.
 *  - Écrire simultanément dans un fichier de log rotatif (optionnel).
 *  - Logguer les événements système et les erreurs.
 *
 * Format d'affichage (conforme aux directives du cahier de charges) :
 *
 *   [HH:mm:ss] ► OUTGOING  CONNECT
 *   ├─ [0] nom_joueur : Alice
 *
 *   [HH:mm:ss] ◄ INCOMING  ROOM_LIST
 *   ├─ [0] salle (liste) :
 *   │    • salle1
 *   │    • salle2
 *   └─ (fin)
 *
 * Hypothèses :
 *  - Le fichier de log est créé dans le répertoire courant sous le nom "gg_<rôle>_<date>.log".
 *  - Si l'écriture fichier échoue, le logger continue de fonctionner en mode console seul.
 *  - Tous les champs d'un message liste (séparés par des virgules) sont affichés individuellement.
 *  - Le DebugLogger est activé par défaut ; il peut être désactivé via setEnabled(false).
 */
public class DebugLogger {

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static volatile DebugLogger instance;

    /**
     * Retourne l'instance unique du DebugLogger.
     * Double-checked locking pour la thread-safety.
     */
    public static DebugLogger getInstance() {
        if (instance == null) {
            synchronized (DebugLogger.class) {
                if (instance == null) {
                    instance = new DebugLogger();
                }
            }
        }
        return instance;
    }

    // -------------------------------------------------------------------------
    // État interne
    // -------------------------------------------------------------------------

    private volatile boolean enabled = true;
    private PrintWriter fileWriter;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final String SEP_ITEM    = "├─ ";
    private static final String SEP_LAST    = "└─ ";
    private static final String SEP_BULLET  = "│    • ";
    private static final String ARROW_IN    = "◄ INCOMING ";
    private static final String ARROW_OUT   = "► OUTGOING ";
    private static final String ARROW_EVT   = "● EVENT    ";
    private static final String ARROW_ERR   = "✖ ERROR    ";

    // -------------------------------------------------------------------------
    // Constructeur privé
    // -------------------------------------------------------------------------

    private DebugLogger() {
        // Fichier de log désactivé par défaut ; appelé init() pour l'activer.
    }

    // -------------------------------------------------------------------------
    // Initialisation du fichier de log
    // -------------------------------------------------------------------------

    /**
     * Initialise le fichier de log.
     * À appeler une seule fois au démarrage du serveur ou du client.
     *
     * @param role "server" ou "client", intégré dans le nom du fichier
     */
    public synchronized void initLogFile(String role) {
        String filename = "gg_" + role + "_" + LocalDateTime.now().format(FILE_FMT) + ".log";
        try {
            fileWriter = new PrintWriter(new FileWriter(filename, true), true);
            logRaw("=== GG Logger démarré — rôle : " + role + " ===");
        } catch (IOException e) {
            System.err.println("[DebugLogger] Impossible d'ouvrir le fichier de log '" + filename + "' : " + e.getMessage());
            System.err.println("[DebugLogger] Mode console uniquement.");
        }
    }

    // -------------------------------------------------------------------------
    // API publique
    // -------------------------------------------------------------------------

    /**
     * Active ou désactive le logger.
     *
     * @param enabled true pour activer, false pour désactiver
     */
    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Logue un message entrant (reçu sur le réseau).
     *
     * Affiche :
     *  - Le type de commande.
     *  - Chaque champ sur une ligne distincte.
     *  - Si un champ est une liste CSV, chaque élément sur une ligne distincte.
     *
     * @param command  le type de commande du message reçu
     * @param fields   la liste des champs du message
     */
    public synchronized void logIncoming(CommandType command, List<String> fields) {
        if (!enabled) return;
        printFormatted(ARROW_IN, command, fields);
    }

    /**
     * Logue un message sortant (envoyé sur le réseau).
     *
     * @param command  le type de commande du message envoyé
     * @param fields   la liste des champs du message
     */
    public synchronized void logOutgoing(CommandType command, List<String> fields) {
        if (!enabled) return;
        printFormatted(ARROW_OUT, command, fields);
    }

    /**
     * Logue un message entrant directement depuis un objet {@link Message}.
     *
     * @param msg le message reçu
     */
    public synchronized void logIncoming(Message msg) {
        if (!enabled) return;
        logIncoming(msg.getCommand(), msg.getFields());
    }

    /**
     * Logue un message sortant directement depuis un objet {@link Message}.
     *
     * @param msg le message envoyé
     */
    public synchronized void logOutgoing(Message msg) {
        if (!enabled) return;
        logOutgoing(msg.getCommand(), msg.getFields());
    }

    /**
     * Logue un message sortant depuis une ligne réseau brute.
     * Parse la ligne pour en extraire commande et champs.
     *
     * @param rawLine la ligne réseau à logguer
     */
    public synchronized void logOutgoingRaw(String rawLine) {
        if (!enabled) return;
        try {
            Message msg = MessageParser.parse(rawLine);
            logOutgoing(msg);
        } catch (ParseException e) {
            logRaw("[DebugLogger] Impossible de parser la ligne sortante : " + rawLine);
        }
    }

    /**
     * Logue un message entrant depuis une ligne réseau brute.
     *
     * @param rawLine la ligne réseau reçue
     */
    public synchronized void logIncomingRaw(String rawLine) {
        if (!enabled) return;
        try {
            Message msg = MessageParser.parse(rawLine);
            logIncoming(msg);
        } catch (ParseException e) {
            logRaw("[DebugLogger] Impossible de parser la ligne entrante : " + rawLine);
        }
    }

    /**
     * Logue un événement système (connexion, déconnexion, démarrage, etc.).
     *
     * @param eventMessage description de l'événement
     */
    public synchronized void logEvent(String eventMessage) {
        if (!enabled) return;
        String line = "[" + now() + "] " + ARROW_EVT + eventMessage;
        print(line);
    }

    /**
     * Logue une erreur avec son exception.
     *
     * @param message description de l'erreur
     * @param e       l'exception associée (peut être null)
     */
    public synchronized void logError(String message, Exception e) {
        if (!enabled) return;
        String line = "[" + now() + "] " + ARROW_ERR + message;
        print(line);
        if (e != null) {
            String causeLine = "         Cause : " + e.getClass().getSimpleName() + " — " + e.getMessage();
            print(causeLine);
        }
    }

    /**
     * Logue une erreur sans exception.
     *
     * @param message description de l'erreur
     */
    public synchronized void logError(String message) {
        logError(message, null);
    }

    // -------------------------------------------------------------------------
    // Méthode centrale de formatage (conforme aux directives)
    // -------------------------------------------------------------------------

    /**
     * Formate et affiche un message GG selon les directives :
     *  - Type du message sur une ligne.
     *  - Chaque champ sur une ligne distincte.
     *  - Si le champ est une liste CSV, chaque élément sur une ligne distincte.
     *
     * @param direction ARROW_IN ou ARROW_OUT
     * @param command   type de commande
     * @param fields    liste des champs
     */
    public synchronized void printFormatted(String direction, CommandType command, List<String> fields) {
        StringBuilder sb = new StringBuilder();

        // Ligne d'en-tête : timestamp + direction + commande
        sb.append("\n[").append(now()).append("] ")
                .append(direction)
                .append(command.toString())
                .append("\n");

        if (fields == null || fields.isEmpty()) {
            sb.append(SEP_LAST).append("(aucun champ)\n");
        } else {
            for (int i = 0; i < fields.size(); i++) {
                String field = fields.get(i);
                boolean isLast = (i == fields.size() - 1);
                String prefix = isLast ? SEP_LAST : SEP_ITEM;

                // Détecter si le champ est une liste CSV
                if (field.contains(",")) {
                    sb.append(prefix).append("[").append(i).append("] (liste) :\n");
                    String[] items = field.split(",", -1);
                    for (String item : items) {
                        sb.append(SEP_BULLET).append(item.trim()).append("\n");
                    }
                } else {
                    sb.append(prefix)
                            .append("[").append(i).append("] ")
                            .append(field)
                            .append("\n");
                }
            }
        }

        print(sb.toString().stripTrailing());
    }

    // -------------------------------------------------------------------------
    // Méthodes internes
    // -------------------------------------------------------------------------

    /**
     * Affiche une ligne sur stdout et dans le fichier si ouvert.
     */
    private void print(String line) {
        System.out.println(line);
        logRaw(line);
    }

    /**
     * Écrit une ligne brute dans le fichier de log (sans duplication stdout).
     */
    private void logRaw(String line) {
        if (fileWriter != null) {
            fileWriter.println(line);
        }
    }

    /**
     * Retourne l'heure courante formatée.
     */
    private String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }

    /**
     * Ferme le fichier de log proprement.
     * À appeler lors de l'arrêt du serveur ou du client.
     */
    public synchronized void close() {
        logRaw("=== GG Logger arrêté ===");
        if (fileWriter != null) {
            fileWriter.flush();
            fileWriter.close();
            fileWriter = null;
        }
    }
}