package server;

import common.DebugLogger;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Gestionnaire de sécurité côté serveur.
 *
 * Responsabilités :
 *  1. Encapsulation TLS/SSL des sockets (wrapTLS).
 *  2. Signature et vérification HMAC-SHA256 des messages.
 *  3. Rate limiting par joueur (anti-flood).
 *  4. Sanitisation des entrées (prévention d'injection de délimiteurs).
 *
 * Hypothèses :
 *  - Le keystore JKS est fourni via le fichier "gg_server.jks" dans le répertoire courant.
 *  - Le mot de passe du keystore est lu depuis la variable d'environnement GG_KEYSTORE_PASS.
 *    Si elle est absente, le fallback "changeit" est utilisé (déconseillé en production).
 *  - Si le fichier JKS est absent, TLS est désactivé avec un avertissement :
 *    les sockets sont utilisés tels quels (mode développement uniquement).
 *  - La clé HMAC est dérivée du mot de passe du keystore ; en production,
 *    elle devrait être une clé dédiée stockée séparément.
 *  - Rate limiting : max {@value #MAX_MESSAGES_PER_WINDOW} messages par fenêtre
 *    de {@value #WINDOW_MS} ms par joueur. Un joueur qui dépasse la limite reçoit
 *    un message GG|ERROR et sa connexion peut être fermée.
 *  - La sanitisation remplace les caractères | et \n par des espaces pour empêcher
 *    l'injection de champs supplémentaires dans un message GG.
 *  - Cette classe est thread-safe.
 */
public class SecurityManager {

    // -------------------------------------------------------------------------
    // Constantes
    // -------------------------------------------------------------------------

    /** Algorithme de signature HMAC. */
    private static final String HMAC_ALGO = "HmacSHA256";

    /** Nom du fichier keystore JKS. */
    private static final String KEYSTORE_FILE = "gg_server.jks";

    /** Variable d'environnement pour le mot de passe keystore. */
    private static final String KEYSTORE_ENV  = "GG_KEYSTORE_PASS";

    /** Mot de passe par défaut (développement uniquement). */
    private static final String KEYSTORE_DEFAULT_PASS = "changeit";

    /** Nombre maximum de messages autorisés par fenêtre temporelle. */
    private static final int MAX_MESSAGES_PER_WINDOW = 30;

    /** Durée de la fenêtre de rate limiting en millisecondes. */
    private static final long WINDOW_MS = 1_000L;

    /** Séparateur HMAC ajouté à la fin d'un message signé. */
    private static final String HMAC_SEPARATOR = "##";

    /**
     * Interrupteur global de sécurité.
     * Mettre à true pour activer TLS, HMAC et rate limiting en production.
     * Laisser à false pendant le développement/les tests : toutes les opérations
     * de sécurité deviennent des no-ops transparents.
     * TODO : passer à true avant la mise en production.
     */
    public static final boolean SECURITY_ENABLED = true;

    // -------------------------------------------------------------------------
    // État
    // -------------------------------------------------------------------------

    /** Contexte SSL initialisé (null si TLS désactivé). */
    private SSLContext sslContext;

    /** Clé HMAC pour la signature des messages. */
    private javax.crypto.SecretKey hmacKey;

    /** TLS activé ou non. */
    private boolean tlsEnabled;

    /** HMAC activé ou non. */
    private boolean hmacEnabled;

    /**
     * Table de rate limiting : nom_joueur → RateLimitEntry.
     * ConcurrentHashMap : accès multi-thread sans bloc synchronized global.
     */
    private final Map<String, RateLimitEntry> rateLimitMap;

    // -------------------------------------------------------------------------
    // Constructeur
    // -------------------------------------------------------------------------

    /**
     * Initialise le SecurityManager.
     * Tente de charger le keystore JKS pour TLS et dérive la clé HMAC.
     * En cas d'échec, bascule en mode dégradé (pas de TLS ni HMAC).
     */
    public SecurityManager() {
        this.rateLimitMap = new ConcurrentHashMap<>();
        initSecurity();
    }

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    /**
     * Charge le keystore et initialise SSLContext + clé HMAC.
     * En cas d'erreur, mode dégradé avec avertissement.
     */
    private void initSecurity() {
        String pass = System.getenv(KEYSTORE_ENV);
        if (pass == null || pass.isBlank()) {
            pass = KEYSTORE_DEFAULT_PASS;
            DebugLogger.getInstance().logEvent(
                    "[SecurityManager] Variable " + KEYSTORE_ENV +
                            " absente — utilisation du mot de passe par défaut (mode dev)."
            );
        }

        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(KEYSTORE_FILE)) {
                keyStore.load(fis, pass.toCharArray());
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm()
            );
            kmf.init(keyStore, pass.toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()
            );
            tmf.init(keyStore);

            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            // Dériver la clé HMAC depuis le mot de passe
            byte[] keyBytes = pass.getBytes(StandardCharsets.UTF_8);
            hmacKey = new SecretKeySpec(keyBytes, HMAC_ALGO);

            tlsEnabled  = true;
            hmacEnabled = true;

            DebugLogger.getInstance().logEvent(
                    "[SecurityManager] TLS initialisé depuis '" + KEYSTORE_FILE + "'."
            );

        } catch (IOException e) {
            DebugLogger.getInstance().logEvent(
                    "[SecurityManager] Keystore introuvable (" + KEYSTORE_FILE +
                            ") — TLS et HMAC désactivés (mode développement)."
            );
            tlsEnabled  = false;
            hmacEnabled = false;

        } catch (GeneralSecurityException e) {
            DebugLogger.getInstance().logError(
                    "[SecurityManager] Erreur d'initialisation TLS/HMAC.", e
            );
            tlsEnabled  = false;
            hmacEnabled = false;
        }
    }

    // -------------------------------------------------------------------------
    // TLS
    // -------------------------------------------------------------------------

    /**
     * Encapsule un socket TCP dans un SSLSocket TLS (mode serveur).
     * Si TLS est désactivé, retourne le socket original sans modification.
     *
     * @param rawSocket socket TCP non chiffré
     * @return SSLSocket si TLS activé, sinon rawSocket
     * @throws IOException si l'encapsulation TLS échoue
     */
    public Socket wrapTLS(Socket rawSocket) throws IOException {
        if (!SECURITY_ENABLED || !tlsEnabled || sslContext == null) {
            DebugLogger.getInstance().logEvent(
                    "[SecurityManager] TLS désactivé — socket utilisé sans chiffrement."
            );
            return rawSocket;
        }

        SSLSocketFactory factory = sslContext.getSocketFactory();
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(
                rawSocket,
                rawSocket.getInetAddress().getHostAddress(),
                rawSocket.getPort(),
                true   // autoClose : ferme rawSocket quand sslSocket est fermé
        );
        sslSocket.setUseClientMode(false); // mode serveur
        sslSocket.startHandshake();

        DebugLogger.getInstance().logEvent(
                "[SecurityManager] TLS handshake réussi avec " +
                        rawSocket.getInetAddress().getHostAddress()
        );
        return sslSocket;
    }

    // -------------------------------------------------------------------------
    // HMAC
    // -------------------------------------------------------------------------

    /**
     * Signe un message GG en ajoutant une signature HMAC-SHA256 à la fin.
     * Format du message signé : "GG|COMMAND|champ1|...##<base64_hmac>"
     *
     * Si HMAC est désactivé, retourne la ligne brute sans modification.
     *
     * @param rawLine la ligne réseau à signer
     * @return la ligne signée
     */
    public String signMessage(String rawLine) {
        if (!SECURITY_ENABLED || !hmacEnabled || hmacKey == null) {
            return rawLine;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(hmacKey);
            byte[] sig = mac.doFinal(rawLine.getBytes(StandardCharsets.UTF_8));
            String b64 = Base64.getEncoder().encodeToString(sig);
            return rawLine + HMAC_SEPARATOR + b64;
        } catch (GeneralSecurityException e) {
            DebugLogger.getInstance().logError(
                    "[SecurityManager] Erreur lors de la signature HMAC.", e
            );
            return rawLine;
        }
    }

    /**
     * Vérifie la signature HMAC d'un message reçu.
     * Si HMAC est désactivé, retourne toujours true.
     *
     * @param signedLine la ligne reçue au format "GG|...|...##<base64_hmac>"
     * @return true si la signature est valide ou si HMAC est désactivé
     */
    public boolean verifySignature(String signedLine) {
        if (!SECURITY_ENABLED || !hmacEnabled || hmacKey == null) {
            return true;
        }
        if (signedLine == null || !signedLine.contains(HMAC_SEPARATOR)) {
            DebugLogger.getInstance().logEvent(
                    "[SecurityManager] Message sans signature HMAC reçu : " + signedLine
            );
            return false;
        }

        int sepIdx  = signedLine.lastIndexOf(HMAC_SEPARATOR);
        String body = signedLine.substring(0, sepIdx);
        String sig  = signedLine.substring(sepIdx + HMAC_SEPARATOR.length());

        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(hmacKey);
            byte[] expected = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            byte[] received = Base64.getDecoder().decode(sig);

            // Comparaison en temps constant pour résister aux timing attacks
            return constantTimeEquals(expected, received);

        } catch (GeneralSecurityException | IllegalArgumentException e) {
            DebugLogger.getInstance().logError(
                    "[SecurityManager] Vérification HMAC échouée.", e
            );
            return false;
        }
    }

    /**
     * Retire la signature HMAC d'une ligne signée pour obtenir la ligne brute GG.
     * Si la ligne ne contient pas de séparateur, elle est retournée telle quelle.
     *
     * @param signedLine la ligne signée
     * @return la ligne GG sans la signature
     */
    public String stripSignature(String signedLine) {
        if (signedLine == null) return null;
        int sepIdx = signedLine.lastIndexOf(HMAC_SEPARATOR);
        if (sepIdx < 0) return signedLine;
        return signedLine.substring(0, sepIdx);
    }

    // -------------------------------------------------------------------------
    // Rate limiting
    // -------------------------------------------------------------------------

    /**
     * Vérifie si le joueur est autorisé à envoyer un message.
     * Implémente un algorithme de fenêtre glissante simple.
     *
     * @param playerName identifiant du joueur (ou adresse IP si non authentifié)
     * @return true si le message est autorisé, false si le joueur est throttlé
     */
    public boolean checkRateLimit(String playerName) {
        if (!SECURITY_ENABLED) return true;
        RateLimitEntry entry = rateLimitMap.computeIfAbsent(
                playerName, k -> new RateLimitEntry()
        );
        return entry.tryConsume();
    }

    /**
     * Réinitialise le compteur de rate limiting d'un joueur.
     * Utile lors d'une déconnexion propre.
     *
     * @param playerName identifiant du joueur
     */
    public void resetRateLimit(String playerName) {
        rateLimitMap.remove(playerName);
    }

    // -------------------------------------------------------------------------
    // Sanitisation
    // -------------------------------------------------------------------------

    /**
     * Sanitise une valeur de champ pour empêcher l'injection de délimiteurs GG.
     *
     * Caractères filtrés :
     *  - '|'  : délimiteur de champs GG → remplacé par '_'
     *  - '\n' : fin de message           → remplacé par ' '
     *  - '\r' : retour chariot           → remplacé par ' '
     *
     * Hypothèse : les noms de joueurs et de salles passent par cette méthode
     * dès leur réception dans ClientHandler.
     *
     * @param input la chaîne à nettoyer
     * @return la chaîne sanitisée, ou chaîne vide si input est null
     */
    public String sanitize(String input) {
        if (input == null) return "";
        return input
                .replace("|",  "_")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
    }

    // -------------------------------------------------------------------------
    // Accesseurs d'état
    // -------------------------------------------------------------------------

    /** @return true si TLS est actif */
    public boolean isTlsEnabled()  { return tlsEnabled; }

    /** @return true si HMAC est actif */
    public boolean isHmacEnabled() { return hmacEnabled; }

    // -------------------------------------------------------------------------
    // Utilitaires internes
    // -------------------------------------------------------------------------

    /**
     * Comparaison en temps constant de deux tableaux d'octets.
     * Prévient les timing attacks lors de la vérification HMAC.
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= (a[i] ^ b[i]);
        }
        return result == 0;
    }

    // -------------------------------------------------------------------------
    // Classe interne : RateLimitEntry
    // -------------------------------------------------------------------------

    /**
     * Entrée de rate limiting pour un joueur.
     * Fenêtre glissante : réinitialise le compteur après WINDOW_MS ms.
     */
    private static final class RateLimitEntry {

        private final AtomicInteger count;
        private volatile long windowStart;

        RateLimitEntry() {
            this.count       = new AtomicInteger(0);
            this.windowStart = System.currentTimeMillis();
        }

        /**
         * Tente de consommer un token.
         *
         * @return true si autorisé, false si la limite est dépassée
         */
        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();

            // Réinitialiser si la fenêtre est expirée
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now;
                count.set(0);
            }

            int current = count.incrementAndGet();
            if (current > MAX_MESSAGES_PER_WINDOW) {
                DebugLogger.getInstance().logEvent(
                        "[SecurityManager] Rate limit dépassé (" +
                                current + "/" + MAX_MESSAGES_PER_WINDOW + " msg/s)."
                );
                return false;
            }
            return true;
        }
    }
}