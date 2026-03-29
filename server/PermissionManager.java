package server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PermissionManager — gestion centralisée des permissions dans le serveur GG.
 *
 * Responsabilités :
 *  - Vérifier si un joueur est admin d'une salle.
 *  - Contrôler les droits : rejoindre, expulser, démarrer une partie.
 *  - Gérer la liste des joueurs bannis (persistante pendant la session serveur).
 *
 * Hypothèses :
 *  - L'admin d'une salle est son créateur (premier joueur à l'avoir créée).
 *  - Un joueur banni ne peut plus rejoindre aucune salle sur ce serveur
 *    (ban global, pas par salle — décision de simplicité pour la démo).
 *  - Un seul PermissionManager est instancié par GGServer et partagé
 *    entre tous les ClientHandler (accès concurrent via ConcurrentHashSet).
 *  - Les vérifications ne lèvent pas d'exception : elles retournent boolean
 *    afin que ClientHandler décide lui-même du message d'erreur à envoyer.
 */
public class PermissionManager {

    // -------------------------------------------------------------------------
    // Ensemble des joueurs bannis (accès concurrent)
    // -------------------------------------------------------------------------

    /**
     * Noms des joueurs bannis du serveur.
     * ConcurrentHashMap.newKeySet() produit un Set thread-safe sans bloc synchronized.
     */
    private final Set<String> bannedPlayers = ConcurrentHashMap.newKeySet();

    // -------------------------------------------------------------------------
    // Vérifications liées aux salles
    // -------------------------------------------------------------------------

    /**
     * Vérifie si un joueur est l'administrateur d'une salle.
     * L'admin est le créateur de la salle (stocké dans Room).
     *
     * @param room       la salle concernée
     * @param playerName le joueur à vérifier
     * @return true si playerName est l'admin de room
     */
    public boolean isAdmin(Room room, String playerName) {
        if (room == null || playerName == null) return false;
        return playerName.equals(room.getAdminName());
    }

    /**
     * Vérifie si un joueur peut rejoindre une salle.
     *
     * Conditions requises (toutes doivent être vraies) :
     *  1. Le joueur n'est pas banni.
     *  2. La salle existe et n'est pas pleine.
     *  3. La salle n'est pas déjà en cours de partie.
     *  4. Le joueur n'est pas déjà dans la salle.
     *
     * @param room       la salle cible
     * @param playerName le joueur qui veut rejoindre
     * @return true si le joueur peut rejoindre
     */
    public boolean canJoin(Room room, String playerName) {
        if (room == null || playerName == null)        return false;
        if (isBanned(playerName))                      return false;
        if (room.isFull())                             return false;
        if (room.isGameInProgress())                   return false;
        if (room.hasPlayer(playerName))                return false;
        return true;
    }

    /**
     * Vérifie si un joueur (admin) peut expulser un autre joueur d'une salle.
     *
     * Conditions :
     *  1. L'expulseur est l'admin de la salle.
     *  2. La cible est bien dans la salle.
     *  3. L'admin ne peut pas s'expulser lui-même.
     *
     * @param room      la salle concernée
     * @param kicker    le joueur qui veut expulser
     * @param target    le joueur à expulser
     * @return true si le kick est autorisé
     */
    public boolean canKick(Room room, String kicker, String target) {
        if (room == null || kicker == null || target == null) return false;
        if (!isAdmin(room, kicker))                           return false;
        if (kicker.equals(target))                            return false;
        if (!room.hasPlayer(target))                          return false;
        return true;
    }

    /**
     * Vérifie si un joueur peut démarrer la partie dans une salle.
     *
     * Conditions :
     *  1. Le joueur est l'admin de la salle.
     *  2. La salle n'est pas déjà en cours de partie.
     *  3. Il y a au moins 2 joueurs dans la salle.
     *
     * @param room       la salle concernée
     * @param playerName le joueur qui veut démarrer
     * @return true si le démarrage est autorisé
     */
    public boolean canStartGame(Room room, String playerName) {
        if (room == null || playerName == null) return false;
        if (!isAdmin(room, playerName))         return false;
        if (room.isGameInProgress())            return false;
        if (room.getPlayerCount() < 2)          return false;
        return true;
    }

    // -------------------------------------------------------------------------
    // Gestion des bans
    // -------------------------------------------------------------------------

    /**
     * Bannit un joueur du serveur.
     * Un joueur banni ne peut plus rejoindre aucune salle.
     *
     * @param playerName nom du joueur à bannir
     */
    public void ban(String playerName) {
        if (playerName != null && !playerName.isBlank()) {
            bannedPlayers.add(playerName);
            System.out.println("[PermissionManager] Joueur banni : " + playerName);
        }
    }

    /**
     * Lève le ban d'un joueur.
     *
     * @param playerName nom du joueur à débannir
     */
    public void unban(String playerName) {
        bannedPlayers.remove(playerName);
    }

    /**
     * Vérifie si un joueur est banni.
     *
     * @param playerName nom du joueur à vérifier
     * @return true si le joueur est banni
     */
    public boolean isBanned(String playerName) {
        if (playerName == null) return false;
        return bannedPlayers.contains(playerName);
    }

    /**
     * Retourne le nombre de joueurs actuellement bannis.
     * Utile pour les logs serveur.
     */
    public int getBannedCount() {
        return bannedPlayers.size();
    }
}
