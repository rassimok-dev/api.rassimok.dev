package dev.rassimok.api.klad;

import io.vertx.core.AsyncResult;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.net.impl.ConnectionBase;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Every WebSocket handshake needs the site's exact Origin and a valid session
 * cookie. WebSockets are not covered by CORS, so without the Origin check any
 * page could open a socket with the player's cookie. Every socket starts
 * paused and reads one frame at a time, so a burst of frames waits in the
 * client's TCP connection and not in the instance's memory.
 */
@Singleton
public class WebSocketHandshakeCheck {

    static final int BODY_SENT = 400;
    static final int NO_SESSION = 401;
    static final int FOREIGN_ORIGIN = 403;
    static final int UNKNOWN_ROOM = 404;
    static final int NOT_IN_RACE = 409;
    static final int OTHER_VERSION = 426;
    static final int TOO_MANY_SOCKETS = 503;
    // Never sent, the client has gone; nginx uses 499 for this.
    static final int CLIENT_GONE = 499;
    private static final String VERSION_HEADER = "Sec-WebSocket-Version";
    private static final String VERSION = "13";
    static final String TOO_LONG = "message_too_long";

    private final String allowedOrigin;
    private final Function<String, LobbyPlayer> signedInPlayer;
    private final OpenSockets openSockets;
    // True while the connection holds a slot. The entry stays until the
    // connection closes, so a kept-alive connection gets one close listener,
    // not one per handshake.
    private final Map<HttpConnection, Boolean> slotHolders = new ConcurrentHashMap<>();

    @Inject
    WebSocketHandshakeCheck(@ConfigProperty(name = "klad.websocket.allowed-origin") String allowedOrigin,
                            Sessions sessions, OpenSockets openSockets) {
        this(allowedOrigin, sessions::signedInPlayer, openSockets);
    }

    WebSocketHandshakeCheck(String allowedOrigin, Function<String, LobbyPlayer> signedInPlayer,
                            OpenSockets openSockets) {
        this.allowedOrigin = allowedOrigin;
        this.signedInPlayer = signedInPlayer;
        this.openSockets = openSockets;
    }

    // The endpoint gets each text message and the socket's sender, but not
    // the socket, so it cannot replace the handlers that keep the count and
    // the read limits.
    void upgrade(RoutingContext context, SocketEndpoint endpoint) {
        HttpServerRequest request = context.request();
        HttpConnection connection = request.connection();
        MultiMap headers = request.headers();
        // The cap and the Origin are checked on the event loop, so a full
        // instance or a foreign page never takes a worker thread.
        if (!headers.contains(HttpHeaders.UPGRADE, HttpHeaders.WEBSOCKET, true)) {
            context.next();
        } else if (holdsSlot(connection)) {
            // Sent right behind a handshake, and routed once that one upgraded.
            // The connection speaks WebSocket now, so no HTTP answer fits, and
            // toWebSocket() would hand over the same socket again.
        } else if (hasBody(headers)) {
            refuse(context, BODY_SENT);
        } else if (!VERSION.equals(headers.get(VERSION_HEADER))) {
            // The older drafts frame an empty message in 2 bytes, so one read
            // would queue three times as many frames as with version 13.
            context.response().putHeader(VERSION_HEADER, VERSION);
            refuse(context, OTHER_VERSION);
        } else if (!takeSlot(connection)) {
            refuse(context, TOO_MANY_SOCKETS);
        } else if (!isAllowedOrigin(request.getHeader(HttpHeaders.ORIGIN))) {
            giveBackSlot(connection);
            refuse(context, FOREIGN_ORIGIN);
        } else {
            Cookie cookie = request.getCookie(Sessions.COOKIE);
            String token = null == cookie ? null : cookie.getValue();
            // The session lookup blocks, so it runs on a worker thread. Its result
            // comes back on the request's event loop, where the upgrade happens.
            Vertx.currentContext().executeBlocking(() -> signedInPlayer.apply(token), false)
                    .onComplete(lookup -> afterLookup(context, connection, lookup, endpoint));
        }
    }

    boolean isAllowedOrigin(String origin) {
        return allowedOrigin.equals(origin);
    }

    static boolean isTooLong(String message) {
        return Rules.SOCKET_MESSAGE_BYTES_MAX < message.getBytes(StandardCharsets.UTF_8).length;
    }

    // A handshake has no body. Vert.x would read one into memory before the
    // upgrade, and over 8192 bytes answer 413 without ending the upgrade.
    private static boolean hasBody(MultiMap headers) {
        String contentLength = headers.get(HttpHeaders.CONTENT_LENGTH);
        return headers.contains(HttpHeaders.TRANSFER_ENCODING) || (null != contentLength && !"0".equals(contentLength));
    }

    // A slot belongs to a connection and comes back once: at a refusal, or
    // when the socket or the connection closes. The connection's close also
    // covers any path that ends in neither, such as an upgrade that never ends.
    private boolean takeSlot(HttpConnection connection) {
        boolean hasSlot = openSockets.tryOpen();
        if (hasSlot && null == slotHolders.put(connection, true)) {
            // The close future takes any number of listeners; the close handler
            // takes one, and Quarkus sets it when the connections are limited.
            ConnectionBase tcpConnection = (ConnectionBase) connection;
            tcpConnection.closeFuture().onComplete(ignored -> {
                giveBackSlot(connection);
                slotHolders.remove(connection);
            });
        }
        return hasSlot;
    }

    private boolean holdsSlot(HttpConnection connection) {
        return Boolean.TRUE.equals(slotHolders.get(connection));
    }

    // Called before a refusal ends its response: that routes the request
    // queued behind it at once, which must not find the slot still held.
    private void giveBackSlot(HttpConnection connection) {
        if (slotHolders.replace(connection, true, false)) {
            openSockets.closed();
        }
    }

    // The upgrade of a gone client would throw and log an error, so it is
    // refused first. The handshake runs one event-loop task after
    // toWebSocket(); a close in between fails the upgrade.
    private void afterLookup(RoutingContext context, HttpConnection connection, AsyncResult<LobbyPlayer> lookup,
                             SocketEndpoint endpoint) {
        if (context.response().closed()) {
            giveBackSlot(connection);
            refuse(context, CLIENT_GONE);
        } else if (lookup.failed()) {
            giveBackSlot(connection);
            context.fail(lookup.cause());
        } else if (null == lookup.result()) {
            giveBackSlot(connection);
            refuse(context, NO_SESSION);
        } else {
            LobbyPlayer player = lookup.result();
            int refusalStatus = endpoint.refusalStatus(player);
            if (SocketEndpoint.NO_REFUSAL != refusalStatus) {
                giveBackSlot(connection);
                refuse(context, refusalStatus);
            } else {
                context.request().toWebSocket().onComplete(upgraded -> {
                    if (upgraded.succeeded()) {
                        open(upgraded.result(), connection, player, endpoint);
                    } else {
                        giveBackSlot(connection);
                    }
                });
            }
        }
    }

    private void open(ServerWebSocket socket, HttpConnection connection, LobbyPlayer player,
                      SocketEndpoint endpoint) {
        SocketSender sender = new SocketSender(socket, connection);
        socket.pause();
        SocketListener listener = null;
        try {
            listener = endpoint.open(player, sender);
        } catch (RuntimeException e) {
            // The connection's close gives the slot back.
            sender.drop();
        }
        if (null != listener) {
            listenTo(socket, connection, sender, listener);
        }
    }

    private void listenTo(ServerWebSocket socket, HttpConnection connection, SocketSender sender,
                          SocketListener listener) {
        socket.closeHandler(ignored -> {
            giveBackSlot(connection);
            listener.onClose();
        });
        socket.exceptionHandler(ignored -> sender.drop());
        socket.frameHandler(frame -> afterFrame(socket, sender, frame));
        socket.textMessageHandler(message -> onText(listener, sender, message));
        socket.fetch(1);
    }

    // The size is checked here and not only by the frame size limit: over
    // that limit the connection is dropped without a close code. A listener
    // that throws is dropped too, because Vert.x would log an error for each
    // of its messages.
    private static void onText(SocketListener listener, SocketSender sender, String message) {
        if (isTooLong(message)) {
            sender.close(SocketSender.POLICY_VIOLATION, TOO_LONG);
        } else {
            try {
                listener.onText(message);
            } catch (RuntimeException e) {
                sender.drop();
            }
        }
    }

    // Vert.x calls this after the endpoint's message handler, and fetches by
    // itself after a ping or a pong.
    private static void afterFrame(ServerWebSocket socket, SocketSender sender, WebSocketFrame frame) {
        // Browsers cannot send pings. Vert.x answers each one with a pong as it
        // is read, even after a close, so a client that pings and does not
        // read would pile up pongs.
        if (frame.isPing()) {
            sender.drop();
        } else if (frame.isText() || frame.isBinary() || frame.isContinuation()) {
            // Fetched at once, the next frame would be handled inside the same
            // read, which goes on through all the bytes read, and its reply
            // would not be flushed until the read ends.
            Vertx.currentContext().runOnContext(ignored -> socket.fetch(1));
        }
    }

    private static void refuse(RoutingContext context, int status) {
        context.response().setStatusCode(status).end();
    }
}
