package dev.rassimok.api.klad;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.impl.ConnectionBase;

/**
 * Sends and closes without waiting for the client. Waiting would hold every
 * later message of the socket in memory while its client does not read, so
 * a socket with too many unfinished sends is closed and dropped instead.
 * One per socket, used on the socket's event loop only.
 */
final class SocketSender {

    static final short NORMAL_CLOSURE = 1000;
    static final short POLICY_VIOLATION = 1008;
    static final String NOT_READING = "not_reading";

    private final ServerWebSocket socket;
    // The socket's own connection is not public API; the handshake request's is.
    private final HttpConnection connection;
    private int unfinishedSends;

    SocketSender(ServerWebSocket socket, HttpConnection connection) {
        this.socket = socket;
        this.connection = connection;
    }

    void send(String text) {
        if (Rules.SOCKET_UNFINISHED_SENDS_MAX <= unfinishedSends) {
            close(POLICY_VIOLATION, NOT_READING);
            drop();
        } else if (!socket.isClosed()) {
            unfinishedSends++;
            socket.writeTextMessage(text).onComplete(ignored -> unfinishedSends--);
        }
    }

    // A client that does not answer the close frame, or does not read it,
    // would hold its slot until Vert.x's 10 s closing timeout, so the drop
    // follows after a short grace. Dropping an ended connection does nothing.
    void close(short code, String reason) {
        socket.close(code, reason);
        Vertx.currentContext().owner().setTimer(Rules.SOCKET_CLOSE_GRACE.toMillis(), ignored -> drop());
    }

    // Ends the socket at once, without a close frame. HttpConnection.close()
    // and Channel.close() both wait until a close frame is written, which a
    // client that does not read never allows; this is the close behind them.
    void drop() {
        ConnectionBase tcpConnection = (ConnectionBase) connection;
        tcpConnection.channelHandlerContext().close();
    }
}
