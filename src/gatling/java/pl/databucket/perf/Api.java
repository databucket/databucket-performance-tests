package pl.databucket.perf;

import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/** What the simulations share: the HTTP protocol with the user's token, the load, the assertions, the update steps. */
public final class Api {

    public static final String DATA = "/api/bucket/" + PerfConfig.BUCKET;

    private Api() {
    }

    /** Signs in once, before the load: every virtual user sends the same token (signing in isn't measured). */
    public static HttpProtocolBuilder protocol() {
        System.out.println(PerfConfig.describe());
        String token = DatabucketClient.signIn(PerfConfig.USER, PerfConfig.PASSWORD, PerfConfig.PROJECT).token();
        return http.baseUrl(PerfConfig.BASE_URL)
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .authorizationHeader("Bearer " + token)
                .header("Databucket-Api-Version", "2");
    }

    /** USERS virtual users at the same time (reached within RAMP_UP), kept for DURATION; a finished one is replaced. */
    public static PopulationBuilder load(ScenarioBuilder scenario) {
        return scenario.injectClosed(
                rampConcurrentUsers(0).to(PerfConfig.USERS).during(PerfConfig.RAMP_UP),
                constantConcurrentUsers(PerfConfig.USERS).during(PerfConfig.DURATION));
    }

    public static List<Assertion> assertions() {
        return List.of(
                global().responseTime().percentile(95.0).lt(PerfConfig.P95),
                global().failedRequests().percent().lt(PerfConfig.MAX_FAILED_PERCENT));
    }

    /** A random pause between DELAY_MIN and DELAY_MAX. */
    public static ChainBuilder delay() {
        return pause(PerfConfig.DELAY_MIN, PerfConfig.DELAY_MAX);
    }

    /** Changes $.perf of the row #{id} (step number and time); never the fields the rules use, so data doesn't run out. */
    public static ChainBuilder modify(String name, int step) {
        return exec(http(name).put(DATA + "/#{id}")
                .body(StringBody(session -> json(Map.of("propertiesToSet", perf(step)))))
                .check(status().is(200)));
    }

    /** Changes $.perf of the row #{id} and releases its reservation. */
    public static ChainBuilder modifyAndRelease(String name, int step) {
        return exec(http(name).put(DATA + "/#{id}")
                .body(StringBody(session -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("propertiesToSet", perf(step));
                    body.put("reserved", false);
                    return json(body);
                }))
                .check(status().is(200)));
    }

    private static Map<String, Object> perf(int step) {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("$.perf.step", step);
        set.put("$.perf.at", Instant.now().toString());
        return set;
    }

    public static String json(Object value) {
        try {
            return DatabucketClient.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
