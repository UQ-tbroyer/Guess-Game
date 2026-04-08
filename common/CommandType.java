package common;

/**
 * Enumération exhaustive de toutes les commandes du protocole GG.
 *
 * Convention réseau : les commandes sont transmises en majuscules dans les messages
 * au format GG|COMMAND|champ1|champ2|...
 *
 * Catégories :
 *  - Connexion       : CONNECT, CONNECTED
 *  - Gestion salles  : CREATE_ROOM, ROOM_CREATED, LIST_ROOMS, ROOM_LIST,
 *                      JOIN_ROOM, JOINED_ROOM, LEAVE_ROOM, LEFT_ROOM
 *  - Administration  : KICK_PLAYER, PLAYER_KICKED
 *  - Démarrage       : START_GAME, GAME_STARTED, PLAY_SERVER, SERVER_GAME_STARTED
 *  - Jeu P2P         : SECRET_SET, GUESS, FEEDBACK, WINNER, NEW_GAME
 *  - Erreur          : ERROR (hypothèse : le serveur peut envoyer une erreur générique)
 */
public enum CommandType {

    // --- Connexion ---
    /** Client → Serveur : GG|CONNECT|nom_joueur */
    CONNECT,
    /** Serveur → Client : GG|CONNECTED|nom_joueur */
    CONNECTED,

    // --- Gestion des salles ---
    /** Client → Serveur : GG|CREATE_ROOM|nom_salle|max_joueurs|max_tentatives */
    CREATE_ROOM,
    /** Serveur → Client : GG|ROOM_CREATED|nom_salle */
    ROOM_CREATED,

    /** Client → Serveur : GG|LIST_ROOMS */
    LIST_ROOMS,
    /** Serveur → Client : GG|ROOM_LIST|salle1,salle2,... */
    ROOM_LIST,

    /** Client → Serveur : GG|JOIN_ROOM|nom_salle */
    JOIN_ROOM,
    /** Serveur → Client : GG|JOINED_ROOM|nom_salle|joueur1,joueur2,... */
    JOINED_ROOM,

    /** Client → Serveur : GG|LEAVE_ROOM|nom_salle */
    LEAVE_ROOM,
    /** Serveur → Client : GG|LEFT_ROOM|nom_salle */
    LEFT_ROOM,

    // --- Administration ---
    /** Admin → Serveur : GG|KICK_PLAYER|nom_salle|nom_joueur */
    KICK_PLAYER,
    /** Serveur → Client : GG|PLAYER_KICKED|nom_joueur */
    PLAYER_KICKED,

    // --- Démarrage de partie ---
    /** Admin → Serveur : GG|START_GAME|nom_salle */
    START_GAME,
    /**
     * Serveur → Tous les clients de la salle :
     * GG|GAME_STARTED|nom_salle|joueur1:ip1:port1,joueur2:ip2:port2,...
     *
     * Hypothèse : chaque entrée de la liste est au format nom:ip:port
     * pour permettre aux clients de se connecter en P2P.
     */
    GAME_STARTED,

    /** Client → Serveur : GG|PLAY_SERVER|max_tentatives */
    PLAY_SERVER,
    /** Serveur → Client : GG|SERVER_GAME_STARTED|max_tentatives */
    SERVER_GAME_STARTED,

    // --- Jeu P2P (échanges directs entre clients) ---
    /**
     * Propriétaire du secret → Pairs :
     * GG|SECRET_SET|nom_joueur
     */
    SECRET_SET,

    /**
     * Devineur → Propriétaire du secret :
     * GG|GUESS|couleur1|couleur2|couleur3|couleur4
     */
    GUESS,

    /**
     * Propriétaire → Devineur :
     * GG|FEEDBACK|couleurs_correctes|positions_correctes
     */
    FEEDBACK,

    /**
     * Propriétaire → Tous les pairs :
     * GG|WINNER|nom_joueur
     */
    WINNER,

    /**
     * Propriétaire → Tous les pairs :
     * GG|NEXT_TURN|nom_joueur
     */
    NEXT_TURN,

    /**
     * Propriétaire → Tous les pairs :
     * GG|TURN_ANNOUNCEMENT|nom_joueur
     */
    TURN_ANNOUNCEMENT,

    /**
     * Joueur → Tous les pairs :
     * GG|PLAYER_OUT|nom_joueur
     */
    PLAYER_OUT,

    /**
     * Admin → Tous les pairs :
     * GG|NEW_GAME
     * Déclenche la réinitialisation de l'état du jeu chez tous les clients.
     */
    NEW_GAME,

    /**
     * Admin → Tous les pairs :
     * GG|SET_ATTEMPTS|nombre
     * Modifie le nombre de tentatives par joueur avant que le secret soit défini.
     */
    SET_ATTEMPTS,

    /**
     * Client → Serveur : GG|GAME_OVER|WIN|joueur or GG|GAME_OVER|LOSE|NONE
     * Notifie la fin d'une partie P2P pour permettre la réutilisation de la salle.
     */
    GAME_OVER,

    // --- Info / notifications ---
    /**
     * Pair → Pair : GG|HELLO|nom_joueur
     * Envoyé dès qu'une connexion P2P sortante est établie pour permettre
     * à la partie réceptrice d'enregistrer l'expéditeur dans sa map de pairs.
     */
    HELLO,

    /**
     * Serveur → Client : GG|INFO|message
     * Message d'information générale affiché au client.
     */
    INFO,

    // --- Erreur générique ---
    /**
     * Serveur → Client : GG|ERROR|message_erreur
     * Hypothèse : utilisé pour signaler toute commande invalide ou permission refusée.
     */
    ERROR;

    /**
     * Convertit une chaîne réseau (insensible à la casse) en CommandType.
     *
     * @param s la chaîne lue sur le réseau (ex: "CONNECT", "LIST_ROOMS")
     * @return le CommandType correspondant
     * @throws ParseException si la commande est inconnue
     */
    public static CommandType fromString(String s) throws ParseException {
        if (s == null || s.isBlank()) {
            throw new ParseException("Commande nulle ou vide.");
        }
        try {
            return CommandType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ParseException("Commande inconnue : '" + s + "'.");
        }
    }

    /**
     * Retourne le nom réseau de la commande (majuscules).
     */
    @Override
    public String toString() {
        return this.name();
    }
}