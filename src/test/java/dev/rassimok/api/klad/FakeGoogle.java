package dev.rassimok.api.klad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Google's token endpoint for the tests. It keeps no sign-ins: the test
 * makes the authorization code itself ({@link #code}), and the code says who
 * signed in, with which nonce and PKCE challenge, and how the endpoint
 * answers. So the test and the endpoint share nothing but the code, also when
 * they run in different class loaders or the service is the native binary.
 * With the init argument {@link #TLS} it serves HTTPS with a certificate
 * made at start, and the service gets a trust store holding only that
 * certificate. The JVM tests use plain HTTP: the test JVM's default TLS
 * context may already be fixed by an earlier test.
 */
public class FakeGoogle implements QuarkusTestResourceLifecycleManager {

    static final String TLS = "tls";
    static final String CLIENT_ID = "fake-client.apps.googleusercontent.com";
    static final String CLIENT_SECRET = "fake-client-secret-5ab3c0d2e1";
    static final String REDIRECT_URI = "https://rassimok.dev/klad/api/login/google/callback";
    static final String ISSUER = "https://accounts.google.com";
    static final String CALLS_PATH = "/calls";
    static final String ANSWER_TOKEN = "token";
    static final String ANSWER_ERROR = "error";
    static final String ANSWER_NOT_JSON = "not-json";
    // Longer than the service waits for the token call.
    static final String ANSWER_SLOW = "slow";
    static final Duration SLOW_ANSWER = Rules.GOOGLE_TOKEN_CALL_TIMEOUT.plusSeconds(2);
    private static final String TOKEN_PATH = "/token";
    private static final String HOST = "127.0.0.1";
    private static final String PASSWORD = "fake-google-store";
    private static final String ALIAS = "fake-google";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AtomicInteger calls = new AtomicInteger();
    private boolean isTls;
    private HttpServer server;
    private ExecutorService executor;

    @Override
    public void init(Map<String, String> initArgs) {
        isTls = Boolean.parseBoolean(initArgs.get(TLS));
    }

    @Override
    public Map<String, String> start() {
        Map<String, String> config = new HashMap<>(Map.of(
                "klad.google.client-id", CLIENT_ID,
                "klad.google.client-secret", CLIENT_SECRET));
        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(HOST), 0);
            if (isTls) {
                Path directory = Files.createDirectories(Path.of("target", "fake-google").toAbsolutePath());
                Path keyStore = directory.resolve("key.p12");
                Path trustStore = directory.resolve("trust.p12");
                makeCertificate(directory, keyStore, trustStore);
                HttpsServer httpsServer = HttpsServer.create(address, 0);
                httpsServer.setHttpsConfigurator(new HttpsConfigurator(serverContext(keyStore)));
                server = httpsServer;
                config.put("javax.net.ssl.trustStore", trustStore.toString());
                config.put("javax.net.ssl.trustStorePassword", PASSWORD);
                config.put("javax.net.ssl.trustStoreType", "PKCS12");
            } else {
                server = HttpServer.create(address, 0);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
        // One thread per request, so a slow answer holds up no other call.
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(TOKEN_PATH, this::token);
        server.createContext(CALLS_PATH, this::calls);
        server.start();
        config.put("klad.google.token-endpoint", baseUrl() + TOKEN_PATH);
        return config;
    }

    @Override
    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** The code Google would put in its return, for this test's sign-in. */
    static String code(String email, Object emailVerified, String nonce, String codeChallenge, String answer) {
        ObjectNode code = JSON.createObjectNode()
                .put("email", email)
                .put("nonce", nonce)
                .put("challenge", codeChallenge)
                .put("answer", answer);
        code.set("emailVerified", JSON.valueToTree(emailVerified));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(code.toString().getBytes(StandardCharsets.UTF_8));
    }

    // Fixed by the code, so a test can look for them in logs and tables.
    static String accessTokenFor(String code) {
        return "ya29.fake-access-" + digest(code).substring(0, 24);
    }

    static String subjectFor(String code) {
        return "fake-subject-" + digest(code).substring(48);
    }

    static String signatureFor(String code) {
        return "fake-signature-" + digest(code).substring(24, 48);
    }

    private String baseUrl() {
        return (isTls ? "https" : "http") + "://" + HOST + ":" + server.getAddress().getPort();
    }

    private void token(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        JsonNode code = decode(form.get("code"));
        int status = 400;
        String body = "{\"error\":\"invalid_grant\",\"error_description\":\"Bad Request\"}";
        if (isRightRequest(exchange, form, code)) {
            String answer = code.get("answer").textValue();
            if (ANSWER_SLOW.equals(answer)) {
                pause(SLOW_ANSWER);
            }
            if (ANSWER_TOKEN.equals(answer) || ANSWER_SLOW.equals(answer)) {
                status = 200;
                body = tokenAnswer(form.get("code"), code);
            } else if (ANSWER_NOT_JSON.equals(answer)) {
                status = 200;
                body = "<html>not json</html>";
            }
        }
        respond(exchange, status, body);
    }

    private void calls(HttpExchange exchange) throws IOException {
        respond(exchange, 200, String.valueOf(calls.get()));
    }

    // What Google checks: the client, the redirect address, and that the
    // verifier belongs to the challenge the code was issued for.
    private static boolean isRightRequest(HttpExchange exchange, Map<String, String> form, JsonNode code) {
        return "POST".equals(exchange.getRequestMethod())
                && "application/x-www-form-urlencoded".equals(exchange.getRequestHeaders().getFirst("Content-Type"))
                && "authorization_code".equals(form.get("grant_type"))
                && CLIENT_ID.equals(form.get("client_id"))
                && CLIENT_SECRET.equals(form.get("client_secret"))
                && REDIRECT_URI.equals(form.get("redirect_uri"))
                && null != code
                && null != form.get("code_verifier")
                && GoogleLogins.codeChallenge(form.get("code_verifier")).equals(code.path("challenge").textValue());
    }

    private static String tokenAnswer(String rawCode, JsonNode code) {
        long now = Instant.now().getEpochSecond();
        ObjectNode claims = JSON.createObjectNode()
                .put("iss", ISSUER)
                .put("azp", CLIENT_ID)
                .put("aud", CLIENT_ID)
                .put("sub", subjectFor(rawCode))
                .put("email", code.path("email").textValue())
                .put("iat", now)
                .put("exp", now + 3600)
                .put("nonce", code.path("nonce").textValue());
        claims.set("email_verified", code.get("emailVerified"));
        String idToken = encode("{\"alg\":\"RS256\",\"kid\":\"fake\",\"typ\":\"JWT\"}") + "." + encode(claims.toString())
                + "." + signatureFor(rawCode);
        return JSON.createObjectNode()
                .put("access_token", accessTokenFor(rawCode))
                .put("expires_in", 3599)
                .put("scope", "openid https://www.googleapis.com/auth/userinfo.email")
                .put("token_type", "Bearer")
                .put("id_token", idToken)
                .toString();
    }

    private static JsonNode decode(String code) {
        JsonNode decoded = null;
        try {
            decoded = null == code ? null : JSON.readTree(Base64.getUrlDecoder().decode(code));
        } catch (IllegalArgumentException | IOException e) {
            decoded = null;
        }
        return decoded;
    }

    private static Map<String, String> form(String body) {
        Map<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (0 < equals) {
                values.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        try (exchange; OutputStream output = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(status, bytes.length);
            output.write(bytes);
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // A self-signed certificate for 127.0.0.1, made with the JDK's keytool,
    // and a trust store holding only it.
    private static void makeCertificate(Path directory, Path keyStore, Path trustStore)
            throws IOException, InterruptedException {
        Path certificate = directory.resolve("certificate.der");
        Files.deleteIfExists(keyStore);
        Files.deleteIfExists(trustStore);
        Files.deleteIfExists(certificate);
        keytool("-genkeypair", "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=" + HOST, "-ext", "SAN=ip:" + HOST + ",dns:localhost",
                "-keystore", keyStore.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD);
        keytool("-exportcert", "-alias", ALIAS, "-keystore", keyStore.toString(), "-storepass", PASSWORD,
                "-file", certificate.toString());
        keytool("-importcert", "-noprompt", "-alias", ALIAS, "-file", certificate.toString(),
                "-keystore", trustStore.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD);
    }

    private static void keytool(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (0 != process.waitFor()) {
            throw new IllegalStateException("keytool failed: " + output);
        }
    }

    private static SSLContext serverContext(Path keyStorePath) throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }
}
