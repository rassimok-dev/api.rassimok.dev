package dev.rassimok.api.klad;

/** What an endpoint hears from one open socket, on the socket's event loop. */
interface SocketListener {

    // A text message of at most Rules.SOCKET_MESSAGE_BYTES_MAX bytes.
    void onText(String message);

    // Once, however the socket ends.
    void onClose();
}
