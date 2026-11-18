package pl.databucket.perf;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Scenario 1: a new row is created reserved by the user, changed twice with random pauses between, and released.
 * <ol>
 * <li>insert a row (reserved)</li>
 * <li>pause DELAY_MIN-DELAY_MAX</li>
 * <li>modify the row</li>
 * <li>pause</li>
 * <li>modify the row and release it</li>
 * </ol>
 */
public class CreateModifyReleaseSimulation extends Simulation {

    private final ScenarioBuilder scenario = scenario("Create, modify, release")
            .exec(http("1. insert a reserved row").post(Api.DATA)
                    .body(StringBody(session -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("properties", Orders.properties(ThreadLocalRandom.current().nextLong(1, 1_000_000_000L)));
                        row.put("reserved", true);
                        return Api.json(row);
                    }))
                    .check(status().is(201), jsonPath("$.id").saveAs("id")))
            .exitHereIfFailed()
            .exec(Api.delay())
            .exec(Api.modify("3. modify", 1))
            .exec(Api.delay())
            .exec(Api.modifyAndRelease("5. modify and release", 2));

    {
        setUp(Api.load(scenario)).protocols(Api.protocol()).assertions(Api.assertions());
    }
}
