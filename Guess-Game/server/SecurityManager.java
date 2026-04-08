package server;

import common.DebugLogger;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
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

    /** Variable d'environnement pour une clé HMAC dédiée (prioritaire sur le keystore). */
    private static final String HMAC_KEY_ENV = "GG_HMAC_KEY";

    /** Nom du fichier où le sel HMAC est persisté entre redémarrages. */
    private static final String SALT_FILE = "gg_hmac.salt";

    /** Longueur du sel HMAC en octets (256 bits). */
    private static final int SALT_LENGTH_BYTES = 32;

    /** Nombre d'itérations PBKDF2. */
    private static final int PBKDF2_ITERATIONS = 100_000;

    /** Longueur en bits de la clé dérivée. */
    private static final int PBKDF2_KEY_BITS = 256;

    /** Nombre maximum de messages autorisés par fenêtre temporelle. */
    private static final int MAX_MESSAGES_PER_WINDOW = 30;

    /** Durée de la fenêtre de rate limiting en millisecondes. */
    private static final long WINDOW_MS = 1_000L;

    /** Nombre de violations de rate limit avant déconnexion forcée. */
    private static final int MAX_RATE_VIOLATIONS = 5;

    /** Nombre maximum de tentatives CONNECT échouées par IP avant blocage. */
    private static final int MAX_AUTH_ATTEMPTS = 5;

    /** Taille maximale d'un message GG en caractères. */
    public static final int MAX_MESSAGE_SIZE = 1_024;

    /** Délai maximum pour s'authentifier (CONNECT) après connexion TCP (ms). */
    public static final int AUTH_TIMEOUT_MS = 30_000;

    /** Délai d'inactivité maximum après authentification (ms). 5 minutes. */
    public static final int IDLE_TIMEOUT_MS = 300_000;

    /** Séparateur HMAC ajouté à la fin d'un message signé. */
    private static final String HMAC_SEPARATOR = "##";

    /**
     * Interrupteur global de sécurité.
     * true = TLS, HMAC et rate limiting sont actifs (mode production).
     * false = toutes les opérations de sécurité deviennent des no-ops (mode dev/test).
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

    /** Compteur de violations de rate limit par identifiant. */
    private final Map<String, AtomicInteger> violationsMap;

    /** Compteur de tentatives CONNECT échouées par adresse IP. */
    private final Map<String, AtomicInteger> authAttemptsMap;

    // -------------------------------------------------------------------------
    // Constructeur
    // -------------------------------------------------------------------------

    /**
     * Initialise le SecurityManager.
     * Tente de charger le keystore JKS pour TLS et dérive la clé HMAC.
     * En cas d'échec, bascule en mode dégradé (pas de TLS ni HMAC).
     */
    public SecurityManager() {
        this.rateLimitMap    = new ConcurrentHashMap<>();
        this.violationsMap   = new ConcurrentHashMap<>();
        this.authAttemptsMap = new ConcurrentHashMap<>();
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

            // Dériver la clé HMAC via PBKDF2 (bien plus sûr que les octets bruts du mot de passe).
            // Une clé dédiée via GG_HMAC_KEY a la priorité.
            String hmacKeyEnv = System.getenv(HMAC_KEY_ENV);
            if (hmacKeyEnv != null && !hmacKeyEnv.isBlank()) {
                byte[] keyBytes = Base64.getDecoder().decode(hmacKeyEnv.trim());
                hmacKey = new SecretKeySpec(keyBytes, HMAC_ALGO);
                DebugLogger.getInstance().logEvent(
                        "[SecurityManager] Clé HMAC chargée depuis " + HMAC_KEY_ENV + "."
                );
            } else {
                try {
                    SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                    PBEKeySpec spec = new PBEKeySpec(
                            pass.toCharArray(), loadOrGenerateSalt(), PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
                    byte[] derived = skf.generateSecret(spec).getEncoded();
                    spec.clearPassword();
                    hmacKey = new SecretKeySpec(derived, HMAC_ALGO);
                    DebugLogger.getInstance().logEvent(
                            "[SecurityManager] Clé HMAC dérivée via PBKDF2WithHmacSHA256."
                    );
                } catch (InvalidKeySpecException e2) {
                    DebugLogger.getInstance().logError(
                            "[SecurityManager] Échec PBKDF2, clé HMAC désactivée.", e2);
                    hmacEnabled = false;
                }
            }

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
    // Sel HMAC dynamique
    // -------------------------------------------------------------------------

    /**
     * Charge le sel HMAC depuis le fichier {@value #SALT_FILE}.
     * Si le fichier n'existe pas ou est corrompu, génère un nouveau sel aléatoire
     * et le persiste pour les prochains démarrages.
     *
     * @return sel de 256 bits pour PBKDF2
     */
    private static byte[] loadOrGenerateSalt() {
        java.nio.file.Path saltPath = Paths.get(SALT_FILE);
        if (Files.exists(saltPath)) {
            try {
                byte[] loaded = Files.readAllBytes(saltPath);
                if (loaded.length == SALT_LENGTH_BYTES) {
                    DebugLogger.getInstance().logEvent(
                            "[SecurityManager] Sel HMAC chargé depuis '" + SALT_FILE + "'.");
                    return loaded;
                }
                DebugLogger.getInstance().logEvent(
                        "[SecurityManager] Sel HMAC tronqué (" + loaded.length + " octets), régénération.");
            } catch (IOException e) {
                DebugLogger.getInstance().logError(
                        "[SecurityManager] Impossible de lire le sel HMAC, régénération.", e);
            }
        }
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        new SecureRandom().nextBytes(salt);
        try {
            Files.write(saltPath, salt);
            DebugLogger.getInstance().logEvent(
                    "[SecurityManager] Nouveau sel HMAC généré et sauvegardé dans '" + SALT_FILE + "'.");
        } catch (IOException e) {
            DebugLogger.getInstance().logError(
                    "[SecurityManager] Impossible de sauvegarder le sel HMAC (fonctionnement en mémoire).", e);
        }
        return salt;
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
        try {
            sslSocket.startHandshake();
        } catch (IOException e) {
            try { sslSocket.close(); } catch (IOException ignored) {}
            throw e;
        }

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
     * Enregistre une violation de sécurité.
     * @return true si le seuil de violations est atteint (déconnexion recommandée)
     */
    public boolean recordViolation(String identifier) {
        if (!SECURITY_ENABLED) return false;
        AtomicInteger count = violationsMap.computeIfAbsent(
                identifier, k -> new AtomicInteger(0));
        int violations = count.incrementAndGet();
        if (violations >= MAX_RATE_VIOLATIONS) {
            DebugLogger.getInstance().logEvent(
                    "[SecurityManager] Seuil de violations atteint pour " + identifier
                            + " (" + violations + "). Déconnexion recommandée.");
            return true;
        }
        return false;
    }

    /**
     * Réinitialise le compteur de violations d'un client.
     */
    public void resetViolations(String identifier) {
        violationsMap.remove(identifier);
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
    // Anti-brute-force CONNECT
    // -------------------------------------------------------------------------

    /**
     * Vérifie si une adresse IP est bloquée pour avoir échoué trop de fois
     * à s'authentifier (commande CONNECT).
     *
     * @param ipAddress adresse IP du client
     * @return true si l'IP est bloquée
     */
    public boolean isAuthBlocked(String ipAddress) {
        if (!SECURITY_ENABLED) return false;
        AtomicInteger attempts = authAttemptsMap.get(ipAddress);
        return attempts != null && attempts.get() >= MAX_AUTH_ATTEMPTS;
    }

    /**
     * Enregistre une tentative CONNECT échouée pour une adresse IP.
     *
     * @param ipAddress adresse IP du client
     * @return true si le seuil de blocage est atteint
     */
    public boolean recordAuthFailure(String ipAddress) {
        if (!SECURITY_ENABLED) return false;
        AtomicInteger count = authAttemptsMap.computeIfAbsent(
                ipAddress, k -> new AtomicInteger(0));
        int attempts = count.incrementAndGet();
        if (attempts >= MAX_AUTH_ATTEMPTS) {
            DebugLogger.getInstance().logEvent(
                    "[SecurityManager] IP " + ipAddress
                    + " bloquée après " + attempts + " tentatives CONNECT échouées.");
            return true;
        }
        return false;
    }

    /**
     * Réinitialise le compteur d'échecs d'authentification après un CONNECT réussi.
     *
     * @param ipAddress adresse IP du client
     */
    public void resetAuthAttempts(String ipAddress) {
        authAttemptsMap.remove(ipAddress);
    }

    // -------------------------------------------------------------------------
    // Sanitisation
    // -------------------------------------------------------------------------

    /**
     * Sanitise une valeur de champ pour empêcher l'injection de délimiteurs GG
     * et les attaques par caractères de contrôle.
     *
     * Caractères filtrés :
     *  - '|'              : délimiteur de champs GG → remplacé par '_'
     *  - '\n', '\r'       : fin de message / retour chariot → remplacé par ' '
     *  - '\t'             : tabulation → remplacé par ' '
     *  - U+0000–U+001F, U+007F : caractères de contrôle ASCII → supprimés
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
                .replace("\t", " ")
                .replaceAll("[\\x00-\\x1F\\x7F]", "")
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