package pl.databucket.perf;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.util.Map;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Scenario 2: a row matching the rules is found and reserved, changed twice with random pauses between, and released.
 * The rules: one of the sets in src/gatling/resources/rules.json, chosen with -Prules=name.
 * <ol>
 * <li>find a free row matching the rules and reserve it (one call: /reserve?limit=1, safe with many users); no free
 * row is a failed request (KO), followed by a pause</li>
 * <li>pause DELAY_MIN-DELAY_MAX</li>
 * <li>modify the row</li>
 * <li>pause</li>
 * <li>modify the row and release it</li>
 * </ol>
 */
public class SearchModifyReleaseSimulation extends Simulation {

    private final String body = Api.json(Map.of("rules", RuleSets.get(PerfConfig.RULES)));

    private final ScenarioBuilder scenario = scenario("Search (" + PerfConfig.RULES + "), modify, release")
            .exec(http("1. find and reserve: " + PerfConfig.RULES)
                    .post(Api.DATA + "/reserve?limit=1&sort=" + PerfConfig.SORT)
                    .body(StringBody(body))
                    .check(status().is(200), jsonPath("$.data[0].id").saveAs("id")))
            // no free row matching the rules (KO): wait before the next user tries, instead of hammering the server
            .doIf(session -> !session.contains("id")).then(Api.delay())
            .exitHereIfFailed()
            .exec(Api.delay())
            .exec(Api.modify("3. modify", 1))
            .exec(Api.delay())
            .exec(Api.modifyAndRelease("5. modify and release", 2));

    {
        System.out.println("Rules " + PerfConfig.RULES + ": " + RuleSets.description(PerfConfig.RULES) + " " + RuleSets.get(PerfConfig.RULES));
        setUp(Api.load(scenario)).protocols(Api.protocol()).assertions(Api.assertions());
    }
}
