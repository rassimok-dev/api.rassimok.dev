package dev.rassimok.api.klad;

/**
 * A socket route as WebSocketHandshakeCheck sees it. Both methods run on the
 * handshake's event loop, after every check of the handshake itself.
 */
interface SocketEndpoint {

    int NO_REFUSAL = 0;

    // The HTTP status that refuses this player's handshake, or NO_REFUSAL.
    int refusalStatus(LobbyPlayer player);

    // Called once the socket is open, before it reads its first frame.
    SocketListener open(LobbyPlayer player, SocketSender sender);
}
