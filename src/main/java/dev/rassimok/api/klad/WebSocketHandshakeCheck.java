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
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

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
    static final int OTHER_VERSION = 426;
    static final int TOO_MANY_SOCKETS = 503;
    // Never sent, the client has gone; nginx uses 499 for this.
    static final int CLIENT_GONE = 499;
    private static final String VERSION_HEADER = "Sec-WebSocket-Version";
    private static final String VERSION = "13";

    private final String allowedOrigin;
    private final Predicate<String> isValidSession;
    private final OpenSockets openSockets;
    // Added just before toWebSocket(), removed when the upgrade fails or the
    // socket closes, so it never holds more connections than the cap has slots.
    private final Set<HttpConnection> upgradedConnections = ConcurrentHashMap.newKeySet();

    @Inject
    WebSocketHandshakeCheck(@ConfigProperty(name = "klad.websocket.allowed-origin") String allowedOrigin,
                            Sessions sessions, OpenSockets openSockets) {
        this(allowedOrigin, sessions::isSignedIn, openSockets);
    }

    WebSocketHandshakeCheck(String allowedOrigin, Predicate<String> isValidSession, OpenSockets openSockets) {
        this.allowedOrigin = allowedOrigin;
        this.isValidSession = isValidSession;
        this.openSockets = openSockets;
    }

    // The endpoint gets each text message and the socket's sender, but not
    // the socket, so it cannot replace the handlers that keep the count and
    // the read limits.
    void upgrade(RoutingContext context, BiConsumer<SocketSender, String> onText) {
        HttpServerRequest request = context.request();
        MultiMap headers = request.headers();
        // The cap and the Origin are checked on the event loop, so a full
        // instance or a foreign page never takes a worker thread.
        if (!headers.contains(HttpHeaders.UPGRADE, HttpHeaders.WEBSOCKET, true)) {
            context.next();
        } else if (upgradedConnections.contains(request.connection())) {
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
        } else if (!openSockets.tryOpen()) {
            refuse(context, TOO_MANY_SOCKETS);
        } else if (!isAllowedOrigin(request.getHeader(HttpHeaders.ORIGIN))) {
            openSockets.closed();
            refuse(context, FOREIGN_ORIGIN);
        } else {
            Cookie cookie = request.getCookie(Sessions.COOKIE);
            String token = null == cookie ? null : cookie.getValue();
            // The session lookup blocks, so it runs on a worker thread. Its result
            // comes back on the request's event loop, where the upgrade happens.
            Vertx.currentContext().executeBlocking(() -> isValidSession.test(token), false)
                    .onComplete(lookup -> afterLookup(context, lookup, onText));
        }
    }

    boolean isAllowedOrigin(String origin) {
        return allowedOrigin.equals(origin);
    }

    // A handshake has no body. Vert.x reads one before the upgrade, and over
    // 8192 bytes it answers 413 without ending the upgrade, so the slot taken
    // for it would never come back.
    private static boolean hasBody(MultiMap headers) {
        String contentLength = headers.get(HttpHeaders.CONTENT_LENGTH);
        return headers.contains(HttpHeaders.TRANSFER_ENCODING) || (null != contentLength && !"0".equals(contentLength));
    }

    // The upgrade of a gone client would throw and log an error, so it is
    // refused first. The handshake runs one event-loop task after
    // toWebSocket(); a close in between fails the upgrade and frees the slot.
    private void afterLookup(RoutingContext context, AsyncResult<Boolean> lookup,
                             BiConsumer<SocketSender, String> onText) {
        if (context.response().closed()) {
            openSockets.closed();
            refuse(context, CLIENT_GONE);
        } else if (lookup.failed()) {
            openSockets.closed();
            context.fail(lookup.cause());
        } else if (!lookup.result()) {
            openSockets.closed();
            refuse(context, NO_SESSION);
        } else {
            HttpConnection connection = context.request().connection();
            upgradedConnections.add(connection);
            context.request().toWebSocket().onComplete(upgraded -> {
                if (upgraded.succeeded()) {
                    open(upgraded.result(), connection, onText);
                } else {
                    closed(connection);
                }
            });
        }
    }

    private void open(ServerWebSocket socket, HttpConnection connection, BiConsumer<SocketSender, String> onText) {
        SocketSender sender = new SocketSender(socket, connection);
        socket.pause();
        socket.closeHandler(ignored -> closed(connection));
        socket.exceptionHandler(ignored -> sender.drop());
        socket.frameHandler(frame -> afterFrame(socket, sender, frame));
        socket.textMessageHandler(message -> onText.accept(sender, message));
        socket.fetch(1);
    }

    private void closed(HttpConnection connection) {
        upgradedConnections.remove(connection);
        openSockets.closed();
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
