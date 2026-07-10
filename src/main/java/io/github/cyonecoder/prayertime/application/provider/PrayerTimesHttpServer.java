package io.github.cyonecoder.prayertime.application.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A minimal HTTP adapter over {@link PrayerTimesApiService}, built on the JDK's
 * bundled {@code com.sun.net.httpserver} (no runtime dependency, no framework).
 *
 * <p>Routes:
 * <pre>
 *   GET /api/v1/cities/{cityId}/prayer-times?date=YYYY-MM-DD&amp;source=auto|official|calculated|compare
 *   GET /api/v1/cities/{cityId}/prayer-times/comparison?date=YYYY-MM-DD
 * </pre>
 */
public final class PrayerTimesHttpServer {

    private static final String PREFIX = "/api/v1/cities/";
    private final PrayerTimesApiService api;
    private HttpServer server;

    public PrayerTimesHttpServer() {
        this(new PrayerTimesApiService());
    }

    public PrayerTimesHttpServer(PrayerTimesApiService api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    /**
     * Starts the server.
     *
     * @param port the port ({@code 0} picks an ephemeral free port)
     * @return the actual bound port
     * @throws IOException if the socket cannot be bound
     */
    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/v1/cities/", this::handle);
        server.setExecutor(null);
        server.start();
        return server.getAddress().getPort();
    }

    /** Stops the server if running. */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                write(exchange, 405, "{\"error\":\"METHOD_NOT_ALLOWED\"}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String rest = path.substring(PREFIX.length()); // {cityId}/prayer-times[/comparison]
            int slash = rest.indexOf('/');
            if (slash < 0) {
                write(exchange, 404, "{\"error\":\"NOT_FOUND\"}");
                return;
            }
            String cityId = URLDecoder.decode(rest.substring(0, slash), StandardCharsets.UTF_8);
            String tail = rest.substring(slash + 1);

            PrayerTimesApiService.Response response;
            if (tail.equals("prayer-times")) {
                response = api.prayerTimes(cityId, query.get("date"), query.get("source"));
            } else if (tail.equals("prayer-times/comparison")) {
                response = api.comparison(cityId, query.get("date"));
            } else {
                write(exchange, 404, "{\"error\":\"NOT_FOUND\"}");
                return;
            }
            write(exchange, response.status(), response.body());
        } catch (RuntimeException e) {
            write(exchange, 500, "{\"error\":\"INTERNAL\",\"message\":\""
                    + String.valueOf(e.getMessage()).replace('"', '\'') + "\"}");
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                params.put(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
