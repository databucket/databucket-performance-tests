package pl.databucket.perf;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings of the tests: Gradle properties ({@code -Pusers=200}, passed on as system properties {@code perf.users}) or
 * environment variables ({@code PERF_USERS=200}); the defaults suit the docker compose of databucket-server.
 */
public final class PerfConfig {

    /** Databucket under test. */
    public static final String BASE_URL = text("baseUrl", "http://databucket.localhost").replaceAll("/+$", "");

    /** The test project and its bucket. */
    public static final String PROJECT = text("project", "perf");
    public static final String BUCKET = text("bucket", "users");

    /** The admin of the project (prepareData: creates the bucket, fills it, reserves rows at the start). */
    public static final String ADMIN = text("admin", "perf-admin");

    /** The accounts of the virtual users: perf-tester-0001 ... (ROBOT in the project), all with the same password. */
    public static final int TESTERS = number("testers", 1000);
    public static final String TESTER_PREFIX = text("testerPrefix", "perf-tester-");
    public static final String PASSWORD = text("password", "Perf-Passw0rd!");

    /** Virtual users working at the same time (each signs in with its own account), started within rampUp seconds. */
    public static final int USERS = number("users", 100);
    public static final Duration RAMP_UP = Duration.ofSeconds(number("rampUp", 30));
    /** How long the load lasts after the ramp-up, seconds. */
    public static final Duration DURATION = Duration.ofSeconds(number("duration", 120));
    /** A random wait of every virtual user before signing in (on top of the ramp-up), ms. */
    public static final Duration START_JITTER = Duration.ofMillis(number("startJitter", 1000));

    /** Share of the virtual users per scenario, in %: create, search, delete. */
    public static final Map<String, Integer> MIX = mix(text("mix", "create:40,search:30,delete:30"));

    /** A random pause between the steps of a scenario, ms. */
    public static final Duration DELAY_MIN = Duration.ofMillis(number("delayMin", 500));
    public static final Duration DELAY_MAX = Duration.ofMillis(number("delayMax", 2000));

    /**
     * How the scenarios find rows: "properties" (search by the rule set -Prules, delete by $.origin) or "tags" (search
     * only by the tag -Ptag, delete only by the tag perf-created; rows created by the test get that tag).
     */
    public static final String FILTER = text("filter", "properties");
    /** Tags of the rows of prepareData (perf-tag-01 ...), and the one searched with filter=tags. */
    public static final int TAGS = number("tags", 10);
    public static final String TAG = text("tag", "perf-tag-03");
    public static final String CREATED_TAG = "perf-created";

    public static String tagName(int number) {
        return "perf-tag-" + String.format("%02d", number);
    }

    /** Scenario search: the rule set (src/gatling/resources/rules.json) and the order of the reserved rows. */
    public static final String RULES = text("rules", "active");
    public static final String SORT = text("sort", "id");

    /** The test fails when the 95th percentile of the response times (ms) or the share of failed requests (%) is higher. */
    public static final int P95 = number("p95", 1000);
    public static final double MAX_FAILED_PERCENT = Double.parseDouble(text("maxFailedPercent", "1"));

    /** prepareData: the SUPER admin, rows in the bucket, history of a new bucket, rows reserved at the start (%). */
    public static final String SUPER_USER = text("superUser", "super");
    public static final String SUPER_PASSWORD = text("superPassword", "super");
    public static final int ROWS = number("rows", 100_000);
    public static final boolean HISTORY = Boolean.parseBoolean(text("history", "true"));
    public static final int RESERVED_PERCENT = number("reservedPercent", 20);

    private PerfConfig() {
    }

    public static String tester(int number) {
        return TESTER_PREFIX + String.format("%04d", number);
    }

    public static String describe() {
        return "Databucket " + BASE_URL + ", project " + PROJECT + ", bucket " + BUCKET
                + " | " + USERS + " users of " + TESTERS + " accounts, mix " + MIX
                + " | filter " + FILTER + ("tags".equals(FILTER) ? " (tag " + TAG + ")" : " (rules " + RULES + ")")
                + " | ramp-up " + RAMP_UP.toSeconds() + " s, then " + DURATION.toSeconds() + " s"
                + " | delay " + DELAY_MIN.toMillis() + "-" + DELAY_MAX.toMillis() + " ms"
                + " | p95 < " + P95 + " ms, failed < " + MAX_FAILED_PERCENT + " %";
    }

    /** "create:40,search:30,delete:30" → {create=40, search=30, delete=30}; the shares must add up to 100. */
    private static Map<String, Integer> mix(String text) {
        Map<String, Integer> mix = new LinkedHashMap<>();
        for (String part : text.split(",")) {
            String[] nameAndShare = part.trim().split(":");
            if (nameAndShare.length != 2) throw new IllegalArgumentException("mix: expected name:percent, got '" + part + "'");
            mix.put(nameAndShare[0].trim(), Integer.parseInt(nameAndShare[1].trim()));
        }
        int sum = mix.values().stream().mapToInt(Integer::intValue).sum();
        if (sum != 100) throw new IllegalArgumentException("mix: the shares must add up to 100 %, got " + sum + " (" + text + ")");
        return mix;
    }

    private static String text(String name, String defaultValue) {
        String value = System.getProperty("perf." + name);
        if (value == null || value.isBlank()) value = System.getenv("PERF_" + name.replaceAll("([A-Z])", "_$1").toUpperCase());
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int number(String name, int defaultValue) {
        return Integer.parseInt(text(name, String.valueOf(defaultValue)));
    }
}
