package pl.databucket.perf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The test data: users (as test data in Databucket), the same for prepareData and the scenario create. Row n gets:
 * login and email, status ACTIVE 60 % / NEW 10 % / BLOCKED 15 % / INACTIVE 15 %, type PREMIUM 20 % / STANDARD 80 %,
 * country one of six, age 18-79, group "group-(n % 5000)" (a few rows per group), two roles.
 * <p>
 * Rows created by the scenario create have {@code origin: "perf-create"}; the rows of prepareData have no origin.
 * The scenarios change only {@code $.perf}, never the fields the rules use, so the data doesn't run out.
 */
public final class TestUsers {

    public static final String CREATED = "perf-create";

    private static final List<String> COUNTRIES = List.of("PL", "DE", "FR", "ES", "IT", "UK");
    private static final List<String> ROLES = List.of("buyer", "seller", "admin", "support", "auditor");

    private TestUsers() {
    }

    public static Map<String, Object> properties(long n) {
        long m = n % 20;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("login", "user-" + n);
        p.put("email", "user-" + n + "@example.com");
        p.put("status", m < 12 ? "ACTIVE" : m < 14 ? "NEW" : m < 17 ? "BLOCKED" : "INACTIVE");
        p.put("type", n % 5 == 0 ? "PREMIUM" : "STANDARD");
        p.put("country", COUNTRIES.get((int) (n % COUNTRIES.size())));
        p.put("age", 18 + (int) ((n * 7) % 62));
        p.put("group", "group-" + (n % 5000));
        p.put("roles", List.of(ROLES.get((int) (n % ROLES.size())), ROLES.get((int) ((n / 5) % ROLES.size()))));
        return p;
    }

    /** A user created during the test: marked with origin, so only these are deleted (by the scenario delete). */
    public static Map<String, Object> created(long n) {
        Map<String, Object> p = properties(n);
        p.put("origin", CREATED);
        return p;
    }
}
