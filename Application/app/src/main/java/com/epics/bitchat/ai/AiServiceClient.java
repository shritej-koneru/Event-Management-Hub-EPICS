package com.epics.bitchat.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Minimal HTTP client for the Person 3 FastAPI service (ai-service), used by
 * the "Real test" buttons in the test hub. Pure HttpURLConnection + org.json
 * (no extra dependencies, no Android imports — meshlib is a pure Java module).
 *
 * <p>Callbacks are delivered on a background thread; callers must marshal to
 * the UI thread themselves (e.g. {@code runOnUiThread}).
 */
public final class AiServiceClient {

    /** Parsed /route response (subset the test UI needs). */
    public static final class RouteResult {
        public final String path;
        public final double totalMeters;
        public final double totalSeconds;
        public final String algorithm;
        public final boolean accessible;

        RouteResult(String path, double totalMeters, double totalSeconds,
                    String algorithm, boolean accessible) {
            this.path = path;
            this.totalMeters = totalMeters;
            this.totalSeconds = totalSeconds;
            this.algorithm = algorithm;
            this.accessible = accessible;
        }
    }

    /** Parsed /recommend item. */
    public static final class RecommendItem {
        public final String eventId;
        public final String title;
        public final String category;
        public final String venue;
        public final double score;
        public final double interest;
        public final double popularity;
        public final double recency;

        RecommendItem(String eventId, String title, String category, String venue,
                      double score, double interest, double popularity, double recency) {
            this.eventId = eventId;
            this.title = title;
            this.category = category;
            this.venue = venue;
            this.score = score;
            this.interest = interest;
            this.popularity = popularity;
            this.recency = recency;
        }
    }

    /** Parsed /assistant response. */
    public static final class AssistantResult {
        public final String answer;
        public final String intent;
        public final String matchedFaq;

        AssistantResult(String answer, String intent, String matchedFaq) {
            this.answer = answer;
            this.intent = intent;
            this.matchedFaq = matchedFaq;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final int TIMEOUT_MS = 4000;

    /**
     * Production base URL of the deployed ai-service (e.g.
     * "https://ai.yourdomain.com"). When set, every caller that passes an
     * empty host uses it — end users never type an address. Empty means
     * development mode: fall back to the emulator alias 10.0.2.2:8000
     * (physical phones then type the PC's LAN IP in the test screens).
     */
    public static final String PRODUCTION_BASE_URL = "";

    private AiServiceClient() {
    }

    /** Accepts "10.0.2.2:8000", "http://10.0.2.2:8000" or empty (default). */
    public static String normalizeBaseUrl(String raw) {
        String host = raw == null ? "" : raw.trim();
        if (host.isEmpty()) {
            host = PRODUCTION_BASE_URL.isEmpty() ? "10.0.2.2:8000" : PRODUCTION_BASE_URL;
        }
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    public static void getRoute(String baseUrl, String from, String to, boolean accessible,
                                String algorithm, Consumer<RouteResult> onSuccess,
                                Consumer<String> onError) {
        String url = baseUrl + "/route?from=" + enc(from) + "&to=" + enc(to)
                + "&accessible=" + accessible + "&algorithm=" + algorithm;
        get(url, body -> {
            try {
                JSONObject json = new JSONObject(body);
                onSuccess.accept(new RouteResult(
                        parsePath(json.opt("path")),
                        json.optDouble("total_meters", 0),
                        json.optDouble("total_seconds", 0),
                        json.optString("algorithm", algorithm),
                        json.optBoolean("accessible", false)));
            } catch (JSONException e) {
                onError.accept("bad JSON: " + e.getMessage());
            }
        }, onError);
    }

    /**
     * Path arrives as a JSON array of venue ids ("[\"Hall 1\", ...]"); render it
     * as "[Hall 1, elevator_1, Library]" for display and map lookup. A plain
     * string is passed through untouched.
     */
    private static String parsePath(Object raw) {
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(array.optString(i, ""));
            }
            return sb.append("]").toString();
        }
        return raw == null ? "" : raw.toString();
    }

    public static void getRecommendations(String baseUrl, String interests, int limit,
                                          Consumer<List<RecommendItem>> onSuccess,
                                          Consumer<String> onError) {
        String url = baseUrl + "/recommend?interests=" + enc(interests) + "&limit=" + limit;
        get(url, body -> {
            try {
                JSONObject root = new JSONObject(body);
                JSONArray items = root.optJSONArray("items");
                List<RecommendItem> out = new ArrayList<>();
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        JSONObject breakdown = item.optJSONObject("breakdown");
                        out.add(new RecommendItem(
                                item.optString("event_id"),
                                item.optString("title"),
                                item.optString("category"),
                                item.optString("venue"),
                                item.optDouble("score", 0),
                                breakdown == null ? 0 : breakdown.optDouble("interest", 0),
                                breakdown == null ? 0 : breakdown.optDouble("popularity", 0),
                                breakdown == null ? 0 : breakdown.optDouble("recency", 0)));
                    }
                }
                onSuccess.accept(out);
            } catch (JSONException e) {
                onError.accept("bad JSON: " + e.getMessage());
            }
        }, onError);
    }

    /** Parsed /position response (server-side weighted-centroid estimate). */
    public static final class PositionResult {
        public final String nodeId;
        public final String name;
        public final double x;
        public final double y;
        public final double distanceMeters;
        public final int anchorsUsed;
        public final int anchorsSkipped;
        public final double confidence;

        PositionResult(String nodeId, String name, double x, double y,
                       double distanceMeters, int anchorsUsed, int anchorsSkipped,
                       double confidence) {
            this.nodeId = nodeId;
            this.name = name;
            this.x = x;
            this.y = y;
            this.distanceMeters = distanceMeters;
            this.anchorsUsed = anchorsUsed;
            this.anchorsSkipped = anchorsSkipped;
            this.confidence = confidence;
        }
    }

    /**
     * POST /position — server-side location from beacon anchors. Each entry
     * of {@code anchors} is {@code {"beacon": "Hall 1", "rssi": -60}} (a
     * measured beacon RSSI) or {@code {"beacon": "Hall 1", "hops": 2}} (a
     * mesh venue advert). {@code floor} may be null to snap on any floor.
     */
    public static void postPosition(String baseUrl, JSONArray anchors, Integer floor,
                                    Consumer<PositionResult> onSuccess,
                                    Consumer<String> onError) {
        try {
            JSONObject body = new JSONObject();
            body.put("anchors", anchors);
            if (floor != null) {
                body.put("floor", floor);
            }
            postJson(baseUrl + "/position", body.toString(), resp -> {
                try {
                    JSONObject json = new JSONObject(resp);
                    onSuccess.accept(new PositionResult(
                            json.optString("node_id"),
                            json.optString("name"),
                            json.optDouble("x", 0),
                            json.optDouble("y", 0),
                            json.optDouble("distance_meters", 0),
                            json.optInt("anchors_used", 0),
                            json.optInt("anchors_skipped", 0),
                            json.optDouble("confidence", 0)));
                } catch (JSONException e) {
                    onError.accept("bad JSON: " + e.getMessage());
                }
            }, onError);
        } catch (JSONException e) {
            onError.accept("bad JSON: " + e.getMessage());
        }
    }

    public static void askAssistant(String baseUrl, String question, boolean accessible,
                                    Consumer<AssistantResult> onSuccess,
                                    Consumer<String> onError) {
        String json = "{\"question\": " + JSONObject.quote(question)
                + ", \"accessibility_mode\": " + accessible + "}";
        postJson(baseUrl + "/assistant", json, body -> {
            try {
                JSONObject parsed = new JSONObject(body);
                onSuccess.accept(new AssistantResult(
                        parsed.optString("answer"),
                        parsed.optString("intent"),
                        parsed.isNull("matched_faq") ? null : parsed.optString("matched_faq")));
            } catch (JSONException e) {
                onError.accept("bad JSON: " + e.getMessage());
            }
        }, onError);
    }

    /** Raw HTTP GET; callbacks arrive on a background thread. */
    public static void get(String url, Consumer<String> onSuccess, Consumer<String> onError) {
        EXECUTOR.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                int code = conn.getResponseCode();
                String body = read(conn);
                if (code != 200) {
                    onError.accept("HTTP " + code + ": " + trim(body));
                    return;
                }
                onSuccess.accept(body);
            } catch (Exception e) {
                onError.accept(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private static void postJson(String url, String json,
                                 Consumer<String> onSuccess, Consumer<String> onError) {
        EXECUTOR.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);
                conn.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
                int code = conn.getResponseCode();
                String body = read(conn);
                if (code != 200) {
                    onError.accept("HTTP " + code + ": " + trim(body));
                    return;
                }
                onSuccess.accept(body);
            } catch (Exception e) {
                onError.accept(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private static String read(HttpURLConnection conn) throws java.io.IOException {
        InputStream stream = conn.getErrorStream() != null
                ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String trim(String body) {
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
