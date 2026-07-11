package com.example.census;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin client over the US Census Bureau data API (api.census.gov/data).
 *
 * <ul>
 *   <li>Uses only the JDK: {@link java.net.HttpURLConnection} for I/O and the
 *       hand-rolled {@link Json} parser, so no third-party jars are bundled.
 *       (HttpURLConnection is used rather than java.net.http.HttpClient so the
 *       component runs on the Java 8 runtime LibreOffice accepts on this
 *       machine; both are JDK standard library.)</li>
 *   <li>Caches every raw JSON response by request URL in a process-wide map so
 *       a Calc recalculation (which may re-invoke every formula) does not hammer
 *       the API. The cache lives for the office session, i.e. as long as the
 *       component stays loaded.</li>
 *   <li>The key is read from the {@code CENSUS_API_KEY} environment variable
 *       (falling back to the {@code census.api.key} Java system property) and
 *       simply omitted from the request if never set -- this class does not
 *       reject a missing key client-side, since whether one is actually
 *       required depends on the endpoint: the data-query endpoint currently
 *       requires a key even for a single low-volume request (it answers a
 *       keyless request with a redirect to an HTML "missing key" page rather
 *       than the documented low-volume allowance), while the metadata and
 *       discovery endpoints ({@code variables.json}, {@code {year}.json}) do
 *       not. A keyless data query is still attempted and, if the API rejects
 *       it, {@link #fetch} turns that redirect into a clear error rather than
 *       failing later with a confusing JSON-parse error. See docs/INSTALL.md.</li>
 * </ul>
 */
final class CensusClient {

    private static final String BASE = "https://api.census.gov/data";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    /** Shared per-session cache: request URL -> parsed JSON root. */
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<String, Object>();

    private CensusClient() {
    }

    /**
     * Resolve the API key. An explicit key (the optional function argument)
     * wins; otherwise fall back to the CENSUS_API_KEY environment variable,
     * then the census.api.key system property. A missing key is not rejected
     * here: {@code null} is returned and the request is sent without a
     * {@code key=} parameter, leaving it to the API (and {@link #fetch}) to
     * decide whether that's acceptable for the endpoint being called.
     */
    private static String resolveApiKey(String explicit) {
        if (explicit != null && !explicit.trim().isEmpty()) {
            return explicit.trim();
        }
        String k = System.getenv("CENSUS_API_KEY");
        if (k == null || k.trim().isEmpty()) {
            k = System.getProperty("census.api.key");
        }
        return (k == null || k.trim().isEmpty()) ? null : k.trim();
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e); // never happens
        }
    }

    /** Strip stray leading/trailing slashes from a dataset path segment. */
    private static String trimSlashes(String dataset) {
        String d = dataset.trim();
        while (d.startsWith("/")) d = d.substring(1);
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        if (d.isEmpty()) {
            throw new IllegalArgumentException("dataset is required");
        }
        return d;
    }

    /** Read an input stream fully as a UTF-8 string (Java 8 compatible). */
    private static String readAll(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    /**
     * GET the given URL, returning the parsed JSON root (a {@code List} or a
     * {@code Map} depending on the endpoint). Responses are cached by full
     * URL for the session.
     */
    private static Object fetch(String url) {
        Object cached = CACHE.get(url);
        if (cached != null) {
            return cached;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            // Surface a missing-key request as the redirect it actually is,
            // rather than silently following it into the HTML "Missing Key"
            // page and failing later with a confusing JSON-parse error.
            conn.setInstanceFollowRedirects(false);

            int status = conn.getResponseCode();
            if (status >= 300 && status < 400) {
                String keyError = conn.getHeaderField("X-DataWebAPI-KeyError");
                if (keyError != null && !keyError.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Census API key required for this request. Supply one via the "
                            + "api_key argument or the CENSUS_API_KEY environment variable "
                            + "(sign up at https://api.census.gov/data/key_signup.html).");
                }
                throw new IllegalArgumentException(
                        "Census API returned an unexpected redirect (HTTP " + status + ") to "
                        + conn.getHeaderField("Location"));
            }
            if (status != 200) {
                // The Census API returns plain-text error bodies (not JSON) for
                // bad requests, e.g. "error: unknown variable 'X'".
                String body = readAll(conn.getErrorStream());
                throw new IllegalArgumentException(
                        "Census API returned HTTP " + status
                        + (body.trim().isEmpty() ? "" : ": " + body.trim()));
            }
            String contentType = conn.getContentType();
            if (contentType != null
                    && contentType.toLowerCase(java.util.Locale.ROOT).contains("html")) {
                throw new IllegalArgumentException(
                        "Census API returned an HTML page instead of JSON for this request "
                        + "-- check that year/dataset/variables/geography are correct.");
            }
            Object root = Json.parse(readAll(conn.getInputStream()));
            CACHE.put(url, root);
            return root;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // Network failure, timeout, DNS, TLS, etc. -> caller maps to a Calc
            // error value.
            throw new IllegalArgumentException("Census request failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Run a data query and return the full result table (header row plus one
     * row per geography), all cells coerced to text -- matching the API's own
     * JSON-array-of-arrays shape with minimal parsing.
     */
    @SuppressWarnings("unchecked")
    static Object[][] table(String year, String dataset, String variables,
                             String forClause, String inClause, String apiKey) {
        if (forClause == null || forClause.trim().isEmpty()) {
            throw new IllegalArgumentException("geography is required");
        }
        String key = resolveApiKey(apiKey);
        StringBuilder url = new StringBuilder(BASE)
                .append('/').append(year).append('/').append(trimSlashes(dataset))
                .append("?get=").append(enc(variables))
                .append("&for=").append(enc(forClause.trim()));
        if (inClause != null && !inClause.trim().isEmpty()) {
            url.append("&in=").append(enc(inClause.trim()));
        }
        if (key != null) {
            url.append("&key=").append(enc(key));
        }

        Object root = fetch(url.toString());
        if (!(root instanceof List)) {
            throw new IllegalArgumentException(
                    "Unexpected Census response shape for " + dataset + "/" + year);
        }
        List<Object> rows = (List<Object>) root;
        Object[][] out = new Object[rows.size()][];
        for (int r = 0; r < rows.size(); r++) {
            Object rowObj = rows.get(r);
            if (!(rowObj instanceof List)) {
                throw new IllegalArgumentException("Malformed row in Census response");
            }
            List<Object> row = (List<Object>) rowObj;
            Object[] cells = new Object[row.size()];
            for (int c = 0; c < row.size(); c++) {
                Object v = row.get(c);
                cells[c] = (v == null) ? "" : String.valueOf(v);
            }
            out[r] = cells;
        }
        return out;
    }

    /** Return a variable's human-readable label from its variables.json metadata. */
    @SuppressWarnings("unchecked")
    static String variableLabel(String year, String dataset, String variable) {
        String url = BASE + "/" + year + "/" + trimSlashes(dataset)
                + "/variables/" + enc(variable) + ".json";
        Object root = fetch(url);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException(
                    "Unexpected Census response shape for variable " + variable);
        }
        Map<String, Object> map = (Map<String, Object>) root;
        Object label = map.get("label");
        if (label != null) {
            return String.valueOf(label);
        }
        Object name = map.get("name");
        if (name != null) {
            return String.valueOf(name);
        }
        throw new IllegalArgumentException(
                "Variable " + variable + " not found in " + dataset + "/" + year);
    }

    /** List the datasets available for a year as (path, title) rows. */
    @SuppressWarnings("unchecked")
    static List<Object[]> datasetList(String year) {
        String url = BASE + "/" + year + ".json";
        Object root = fetch(url);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Unexpected Census discovery response for " + year);
        }
        Object dsObj = ((Map<String, Object>) root).get("dataset");
        if (!(dsObj instanceof List)) {
            throw new IllegalArgumentException("No datasets listed for " + year);
        }
        List<Object[]> out = new ArrayList<Object[]>();
        for (Object o : (List<Object>) dsObj) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> entry = (Map<String, Object>) o;
            Object cds = entry.get("c_dataset");
            String path = "";
            if (cds instanceof List) {
                StringBuilder sb = new StringBuilder();
                for (Object seg : (List<Object>) cds) {
                    if (sb.length() > 0) sb.append('/');
                    sb.append(String.valueOf(seg));
                }
                path = sb.toString();
            }
            Object title = entry.get("title");
            out.add(new Object[] { path, title == null ? "" : String.valueOf(title) });
        }
        return out;
    }
}
