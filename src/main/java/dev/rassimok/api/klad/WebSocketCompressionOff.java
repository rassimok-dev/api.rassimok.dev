package dev.rassimok.api.klad;

import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.HttpServerOptions;
import jakarta.inject.Singleton;

/**
 * Vert.x accepts both WebSocket compression extensions by default. With
 * either one, the frame and message limits count compressed bytes, so a
 * small frame could inflate to megabytes before it is refused.
 */
@Singleton
public class WebSocketCompressionOff implements HttpServerOptionsCustomizer {

    @Override
    public void customizeHttpServer(HttpServerOptions options) {
        turnOffCompression(options);
    }

    @Override
    public void customizeHttpsServer(HttpServerOptions options) {
        turnOffCompression(options);
    }

    @Override
    public void customizeDomainSocketServer(HttpServerOptions options) {
        turnOffCompression(options);
    }

    private static void turnOffCompression(HttpServerOptions options) {
        options.setPerMessageWebSocketCompressionSupported(false);
        options.setPerFrameWebSocketCompressionSupported(false);
    }
}
