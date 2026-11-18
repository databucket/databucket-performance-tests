package pl.databucket.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How the scenarios find rows, by -Pfilter:
 * <ul>
 * <li><b>properties</b>: search by the rule set -Prules (+ only rows of prepareData: {@code $.origin} is null);
 * delete finds rows created by the test by {@code $.origin == perf-create}</li>
 * <li><b>tags</b>: search only by the tag -Ptag ({@code tagId}); rows created by the test get the tag perf-created,
 * delete finds them only by that tag. No property is used in the rules.</li>
 * </ul>
 *
 * @param createdTagId the tag of the rows created by the test (tags), or null
 */
public record Filters(String name, String searchBody, String deleteBody, Long createdTagId) {

    public static Filters fromConfig() {
        if ("tags".equals(PerfConfig.FILTER)) {
            Map<String, Long> tags = tagIds();
            long searched = id(tags, PerfConfig.TAG);
            long created = id(tags, PerfConfig.CREATED_TAG);
            return new Filters("tag " + PerfConfig.TAG,
                    Scenarios.json(Map.of("rules", List.of(List.of("tagId", "==", searched)))),
                    Scenarios.json(Map.of("rules", List.of(List.of("tagId", "==", created), List.of("reserved", "==", false)))),
                    created);
        }
        if (!"properties".equals(PerfConfig.FILTER))
            throw new IllegalArgumentException("-Pfilter: properties or tags, got '" + PerfConfig.FILTER + "'");
        // only the rows of prepareData: the rows created during the test belong to the scenarios create and delete
        ArrayNode rules = RuleSets.get(PerfConfig.RULES).deepCopy();
        rules.addArray().add("$.origin").add("==").addNull();
        return new Filters(PerfConfig.RULES,
                Scenarios.json(Map.of("rules", rules)),
                Scenarios.json(Map.of("rules", List.of(List.of("$.origin", "==", TestUsers.CREATED), List.of("reserved", "==", false)))),
                null);
    }

    /** Names → ids of the tags of the project (read as the admin: the testers can't list tags). */
    public static Map<String, Long> tagIds() {
        DatabucketClient admin = DatabucketClient.signIn(PerfConfig.ADMIN, PerfConfig.PASSWORD, PerfConfig.PROJECT);
        Map<String, Long> ids = new HashMap<>();
        for (JsonNode tag : admin.get("/api/tags")) ids.put(tag.path("name").asText(), tag.path("id").asLong());
        return ids;
    }

    private static long id(Map<String, Long> tags, String name) {
        Long id = tags.get(name);
        if (id == null) throw new IllegalStateException("No tag '" + name + "' in the project; run ./gradlew prepareData");
        return id;
    }
}
