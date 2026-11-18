package pl.databucket.perf;

import java.time.Duration;

/**
 * Settings of the tests: Gradle properties ({@code -Pusers=20}, passed on as system properties {@code perf.users}) or
 * environment variables ({@code PERF_USERS=20}); the defaults suit the docker compose of databucket-server.
 */
public final class PerfConfig {

    /** Databucket under test. */
    public static final String BASE_URL = text("baseUrl", "http://databucket.localhost").replaceAll("/+$", "");

    /** The user calling the API (ADMIN in the test project; created by prepareData) and its project and bucket. */
    public static final String USER = text("user", "perf-admin");
    public static final String PASSWORD = text("password", "Perf-Passw0rd!");
    public static final String PROJECT = text("project", "perf");
    public static final String BUCKET = text("bucket", "orders");

    /** Load: virtual users working at the same time, reached within rampUp seconds, kept for duration seconds. */
    public static final int USERS = number("users", 10);
    public static final Duration RAMP_UP = Duration.ofSeconds(number("rampUp", 10));
    public static final Duration DURATION = Duration.ofSeconds(number("duration", 60));

    /** A random pause between the steps of a scenario, in milliseconds (a user thinking / a system processing). */
    public static final Duration DELAY_MIN = Duration.ofMillis(number("delayMin", 500));
    public static final Duration DELAY_MAX = Duration.ofMillis(number("delayMax", 2000));

    /** Scenario 2: the name of the rule set (src/gatling/resources/rules.json) and how the reserved row is chosen. */
    public static final String RULES = text("rules", "new-orders");
    public static final String SORT = text("sort", "id");

    /** The test fails when the 95th percentile of the response times (ms) or the share of failed requests (%) is higher. */
    public static final int P95 = number("p95", 1000);
    public static final double MAX_FAILED_PERCENT = Double.parseDouble(text("maxFailedPercent", "1"));

    /** prepareData: the SUPER admin creating the project and user, rows to have in the bucket, history of the bucket. */
    public static final String SUPER_USER = text("superUser", "super");
    public static final String SUPER_PASSWORD = text("superPassword", "super");
    public static final int ROWS = number("rows", 100_000);
    public static final boolean HISTORY = Boolean.parseBoolean(text("history", "true"));

    private PerfConfig() {
    }

    public static String describe() {
        return "Databucket " + BASE_URL + ", project " + PROJECT + ", bucket " + BUCKET + ", user " + USER
                + " | " + USERS + " users (ramp-up " + RAMP_UP.toSeconds() + " s, " + DURATION.toSeconds() + " s)"
                + " | delay " + DELAY_MIN.toMillis() + "-" + DELAY_MAX.toMillis() + " ms"
                + " | p95 < " + P95 + " ms, failed < " + MAX_FAILED_PERCENT + " %";
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
