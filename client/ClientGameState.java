package client;

/**
 * ClientGameState — état de navigation du client dans l'application.
 *
 * Utilisé par GGClient et CLIHandler pour savoir quelles commandes
 * sont valides à un moment donné et pour afficher le bon contexte
 * dans l'interface console.
 *
 * Transitions d'état :
 *
 *   DISCONNECTED
 *       │ CONNECT envoyé → réponse CONNECTED reçue
 *       ▼
 *   LOBBY
 *       │ JOIN_ROOM envoyé → réponse JOINED_ROOM reçue
 *       ▼
 *   IN_ROOM ◄──────────────────────────────────────────────────┐
 *       │ GAME_STARTED reçu                                     │
 *       ▼                                                       │
 *   IN_GAME                                                     │
 *       │ WINNER reçu OU tentatives épuisées                    │
 *       ▼                                                       │
 *   GAME_OVER ──────────────────── NEW_GAME reçu ──────────────┘
 *       │ LEAVE_ROOM envoyé
 *       ▼
 *   LOBBY
 *
 * Hypothèse : PLAYER_KICKED force le retour à LOBBY depuis n'importe quel état.
 */
public enum ClientGameState {

    /**
     * Avant l'envoi de GG|CONNECT ou après une déconnexion.
     * Seule commande autorisée : GG|CONNECT|nom_joueur.
     */
    DISCONNECTED,

    /**
     * Connecté au serveur, pas dans une salle.
     * Commandes autorisées : LIST_ROOMS, CREATE_ROOM, JOIN_ROOM.
     */
    LOBBY,

    /**
     * Dans une salle, en attente du démarrage de la partie.
     * Commandes autorisées : LEAVE_ROOM, START_GAME (admin), KICK_PLAYER (admin).
     */
    IN_ROOM,

    /**
     * Partie en cours — communications P2P actives.
     * Commandes autorisées : GUESS (si pas détenteur), SECRET_SET (si détenteur).
     */
    IN_GAME,

    /**
     * Partie terminée — un gagnant a été annoncé ou les tentatives sont épuisées.
     * Commandes autorisées : NEW_GAME (admin de la salle), LEAVE_ROOM.
     */
    GAME_OVER;

    /**
     * Retourne une description lisible de l'état pour l'affichage console.
     */
    public String toDisplayString() {
        return switch (this) {
            case DISCONNECTED -> "Non connecté";
            case LOBBY        -> "Lobby (connecté, hors salle)";
            case IN_ROOM      -> "Dans une salle (en attente)";
            case IN_GAME      -> "Partie en cours";
            case GAME_OVER    -> "Partie terminée";
        };
    }
}
