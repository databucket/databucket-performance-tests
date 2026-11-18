package pl.databucket.perf;

import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.http.HttpDsl.http;

/**
 * The load: USERS virtual users split among the scenarios by MIX (e.g. create 40 %, search 30 %, delete 30 %),
 * every scenario's users started evenly within RAMP_UP, all working until RAMP_UP + DURATION. See {@link Scenarios}.
 */
public class DatabucketSimulation extends Simulation {

    {
        System.out.println(PerfConfig.describe());
        System.out.println("Rules of search: " + PerfConfig.RULES + " - " + RuleSets.description(PerfConfig.RULES));

        List<PopulationBuilder> populations = new ArrayList<>();
        int assigned = 0;
        List<Map.Entry<String, Integer>> mix = new ArrayList<>(PerfConfig.MIX.entrySet());
        for (int i = 0; i < mix.size(); i++) {
            // the last scenario takes the rest, so the users add up to USERS
            int users = i == mix.size() - 1 ? PerfConfig.USERS - assigned
                    : (int) Math.round(PerfConfig.USERS * mix.get(i).getValue() / 100.0);
            assigned += users;
            System.out.println("  " + mix.get(i).getKey() + ": " + users + " users");
            if (users > 0)
                populations.add(Scenarios.byName(mix.get(i).getKey())
                        .injectOpen(rampUsers(users).during(PerfConfig.RAMP_UP)));
        }
        if (PerfConfig.USERS > PerfConfig.TESTERS)
            System.out.println("  note: " + PerfConfig.USERS + " users share " + PerfConfig.TESTERS + " accounts");

        setUp(populations)
                .protocols(http.baseUrl(PerfConfig.BASE_URL).acceptHeader("application/json").contentTypeHeader("application/json"))
                // the rounds running at the end finish (up to two pauses), then the test stops
                .maxDuration(PerfConfig.RAMP_UP.plus(PerfConfig.DURATION).plus(PerfConfig.DELAY_MAX.multipliedBy(2))
                        .plus(PerfConfig.START_JITTER).plus(Duration.ofSeconds(30)))
                .assertions(
                        global().responseTime().percentile(95.0).lt(PerfConfig.P95),
                        global().failedRequests().percent().lt(PerfConfig.MAX_FAILED_PERCENT));
    }

    /** Right before the load starts: the end of the test is counted from here; the settings kept for keepReport. */
    @Override
    public void before() {
        Scenarios.start();
        try {
            java.nio.file.Path file = java.nio.file.Path.of("build", "reports", "gatling", "last-run.txt");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, "Started " + java.time.LocalDateTime.now().withNano(0) + "\n"
                    + PerfConfig.describe().replace(" | ", "\n") + "\n"
                    + "rules " + PerfConfig.RULES + " = " + RuleSets.get(PerfConfig.RULES) + ", sort " + PerfConfig.SORT + "\n");
        } catch (java.io.IOException e) {
            System.out.println("Can't write the settings of the run: " + e.getMessage());
        }
    }
}
