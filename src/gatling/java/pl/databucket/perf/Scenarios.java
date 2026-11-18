package pl.databucket.perf;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * The scenarios. Every virtual user takes the next account of the pool (perf-tester-0001...), waits a random moment,
 * signs in with it, and repeats its scenario until the end of the test. A failed step ends the round (the rest of it
 * is skipped) and is followed by a pause.
 * <ul>
 * <li><b>create</b>: insert a user, reserved → pause → modify → pause → modify and release</li>
 * <li><b>search</b>: find a free user matching the rules and reserve it → pause → modify → pause → modify and release</li>
 * <li><b>delete</b>: find users created (and finished) by the scenario create → pause → delete one of them</li>
 * </ul>
 */
public final class Scenarios {

    static final String DATA = "/api/bucket/" + PerfConfig.BUCKET;

    /** When the rounds stop being started: set when the simulation starts. */
    private static volatile long end;

    private Scenarios() {
    }

    static void start() {
        end = System.currentTimeMillis() + PerfConfig.RAMP_UP.toMillis() + PerfConfig.DURATION.toMillis();
    }

    /** The accounts, one per virtual user, shared by all scenarios (again from the first one when there are more users). */
    private static final Iterator<Map<String, Object>> ACCOUNTS = new Iterator<>() {
        private final AtomicInteger next = new AtomicInteger();

        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public Map<String, Object> next() {
            return Map.of("username", PerfConfig.tester(next.getAndIncrement() % PerfConfig.TESTERS + 1));
        }
    };

    public static ScenarioBuilder byName(String name, Filters filters) {
        return switch (name) {
            case "create" -> user("create", create(filters));
            case "search" -> user("search", search(filters));
            case "delete" -> user("delete", delete(filters));
            default -> throw new IllegalArgumentException("Unknown scenario in mix: '" + name + "' (create, search, delete)");
        };
    }

    // ---- the life of a virtual user

    private static ScenarioBuilder user(String name, ChainBuilder round) {
        return scenario(name)
                .feed(ACCOUNTS)
                .exec(pause(Duration.ZERO, PerfConfig.START_JITTER))
                .exec(http("sign in").post("/api/public/signin")
                        .body(StringBody(session -> json(Map.of("username", session.getString("username"), "password", PerfConfig.PASSWORD))))
                        .check(status().is(200), jsonPath("$.token").saveAs("token")))
                .exitHereIfFailed()
                .asLongAs(session -> System.currentTimeMillis() < end).on(
                        exec(session -> session.markAsSucceeded().remove("id").remove("ids"))
                                .exitBlockOnFail().on(round)
                                .doIf(Session::isFailed).then(delay()));
    }

    // ---- create

    private static ChainBuilder create(Filters filters) {
        return exec(authorized(http("create 1. insert a user (reserved)").post(DATA)
                .body(StringBody(session -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("properties", TestUsers.created(ThreadLocalRandom.current().nextLong(1, 1_000_000_000L)));
                    row.put("reserved", true);
                    if (filters.createdTagId() != null) row.put("tagId", filters.createdTagId());
                    return json(row);
                }))
                .check(status().is(201), jsonPath("$.id").saveAs("id"))))
                .exec(delay())
                .exec(modify("create 3. modify", 1))
                .exec(delay())
                .exec(modifyAndRelease("create 5. modify and release", 2));
    }

    // ---- search

    private static ChainBuilder search(Filters filters) {
        return exec(authorized(http("search 1. find and reserve: " + filters.name())
                .post(DATA + "/reserve?limit=1&sort=" + PerfConfig.SORT)
                .body(StringBody(filters.searchBody()))
                .check(status().is(200), jsonPath("$.data[0].id").saveAs("id"))))
                .exec(delay())
                .exec(modify("search 3. modify", 1))
                .exec(delay())
                .exec(modifyAndRelease("search 5. modify and release", 2));
    }

    // ---- delete

    /** Finds users created by the scenario create and released by it (finished): never more deleted than created. */
    private static ChainBuilder delete(Filters filters) {
        return exec(authorized(http("delete 1. find created users").post(DATA + "/get?limit=20")
                .body(StringBody(filters.deleteBody()))
                .check(status().is(200), jsonPath("$.data[*].id").findAll().optional().saveAs("ids"))))
                .doIfOrElse(session -> session.contains("ids")).then(
                        exec(session -> {
                            List<Object> ids = session.getList("ids");
                            return session.set("id", ids.get(ThreadLocalRandom.current().nextInt(ids.size())));
                        })
                                .exec(delay())
                                .exec(authorized(http("delete 3. delete a user").delete(DATA + "/#{id}")
                                        .check(status().is(200)))))
                // nothing created yet (or all taken): wait, as a user would
                .orElse(delay());
    }

    // ---- shared steps

    private static HttpRequestActionBuilder authorized(HttpRequestActionBuilder request) {
        return request.header("Authorization", "Bearer #{token}").header("Databucket-Api-Version", "2");
    }

    private static ChainBuilder delay() {
        return pause(PerfConfig.DELAY_MIN, PerfConfig.DELAY_MAX);
    }

    /** Changes $.perf of the row #{id}; never the fields the rules use. */
    private static ChainBuilder modify(String name, int step) {
        return exec(authorized(http(name).put(DATA + "/#{id}")
                .body(StringBody(session -> json(Map.of("propertiesToSet", perf(session, step)))))
                .check(status().is(200))));
    }

    /** Changes $.perf of the row #{id} and releases its reservation. */
    private static ChainBuilder modifyAndRelease(String name, int step) {
        return exec(authorized(http(name).put(DATA + "/#{id}")
                .body(StringBody(session -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("propertiesToSet", perf(session, step));
                    body.put("reserved", false);
                    return json(body);
                }))
                .check(status().is(200))));
    }

    private static Map<String, Object> perf(Session session, int step) {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("$.perf.step", step);
        set.put("$.perf.by", session.getString("username"));
        set.put("$.perf.at", Instant.now().toString());
        return set;
    }

    static String json(Object value) {
        try {
            return DatabucketClient.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
