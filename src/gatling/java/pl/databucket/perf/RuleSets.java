package pl.databucket.perf;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The rule sets of scenario 2, from src/gatling/resources/rules.json; one is chosen before the test (-Prules=...). */
public final class RuleSets {

    private RuleSets() {
    }

    /** The rules of the set, as JSON (e.g. [["$.status", "==", "NEW"]]). */
    public static JsonNode get(String name) {
        JsonNode all = all();
        JsonNode rules = all.path(name).path("rules");
        if (rules.isMissingNode()) {
            List<String> names = new ArrayList<>();
            all.fieldNames().forEachRemaining(names::add);
            throw new IllegalArgumentException("No rule set '" + name + "' in rules.json; there are: " + names);
        }
        return rules;
    }

    public static String description(String name) {
        return all().path(name).path("description").asText("");
    }

    private static JsonNode all() {
        try (InputStream in = RuleSets.class.getResourceAsStream("/rules.json")) {
            return DatabucketClient.MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
