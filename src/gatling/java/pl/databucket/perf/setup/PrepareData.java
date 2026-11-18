package pl.databucket.perf.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pl.databucket.perf.DatabucketClient;
import pl.databucket.perf.Orders;
import pl.databucket.perf.PerfConfig;

import static pl.databucket.perf.DatabucketClient.MAPPER;

/**
 * Prepares the data for the tests, idempotent ({@code ./gradlew prepareData -Prows=100000}): the project, its ADMIN
 * user and the bucket (created when missing), then orders inserted until the bucket has the given number of rows.
 */
public final class PrepareData {

    private static final int BATCH = 1000;
    private static final int ADMIN = 2;
    private static final int AGENT = 5;

    public static void main(String[] args) {
        System.out.println("Preparing: " + PerfConfig.describe());
        DatabucketClient superAdmin = DatabucketClient.signIn(PerfConfig.SUPER_USER, PerfConfig.SUPER_PASSWORD, null);

        long projectId = project(superAdmin);
        user(superAdmin, projectId);

        DatabucketClient admin = DatabucketClient.signIn(PerfConfig.USER, PerfConfig.PASSWORD, PerfConfig.PROJECT);
        bucket(admin);
        rows(admin);
    }

    private static long project(DatabucketClient superAdmin) {
        for (JsonNode project : superAdmin.get("/api/manage/projects")) {
            if (PerfConfig.PROJECT.equals(project.path("name").asText())) {
                System.out.println("Project " + PerfConfig.PROJECT + ": exists (" + project.path("id").asLong() + ")");
                return project.path("id").asLong();
            }
        }
        JsonNode created = superAdmin.post("/api/manage/projects", MAPPER.createObjectNode()
                .put("name", PerfConfig.PROJECT).put("description", "Performance tests").put("enabled", true), 201);
        System.out.println("Project " + PerfConfig.PROJECT + ": created (" + created.path("id").asLong() + ")");
        return created.path("id").asLong();
    }

    private static void user(DatabucketClient superAdmin, long projectId) {
        for (JsonNode user : superAdmin.get("/api/manage/users")) {
            if (PerfConfig.USER.equals(user.path("username").asText())) {
                System.out.println("User " + PerfConfig.USER + ": exists");
                return;
            }
        }
        ObjectNode user = MAPPER.createObjectNode().put("username", PerfConfig.USER).put("enabled", true);
        user.putArray("rolesIds").add(ADMIN);
        user.putArray("projectsIds").add(projectId);
        superAdmin.post("/api/manage/users", user, 201);
        // a new user has no usable password: a temporary one, then changed, so it isn't a forced change any more
        String temporary = "tmp-" + PerfConfig.PASSWORD;
        superAdmin.post("/api/manage/users/password/reset", MAPPER.createObjectNode()
                .put("username", PerfConfig.USER).put("password", temporary), 200);
        DatabucketClient first = DatabucketClient.signIn(PerfConfig.USER, temporary, null);
        first.post("/api/users/password/change", MAPPER.createObjectNode().put("username", PerfConfig.USER)
                .put("password", temporary).put("newPassword", PerfConfig.PASSWORD), 200);
        System.out.println("User " + PerfConfig.USER + ": created (ADMIN in " + PerfConfig.PROJECT + ")");
    }

    private static void bucket(DatabucketClient admin) {
        for (JsonNode bucket : admin.get("/api/buckets")) {
            if (PerfConfig.BUCKET.equals(bucket.path("name").asText())) {
                System.out.println("Bucket " + PerfConfig.BUCKET + ": exists (history " + bucket.path("history").asBoolean() + ")");
                return;
            }
        }
        ObjectNode bucket = MAPPER.createObjectNode().put("name", PerfConfig.BUCKET).put("history", PerfConfig.HISTORY)
                .put("protectedData", false).put("roleId", AGENT);
        bucket.putObject("icon").put("name", "Storage");
        admin.post("/api/buckets", bucket, 201);
        System.out.println("Bucket " + PerfConfig.BUCKET + ": created (history " + PerfConfig.HISTORY + ")");
    }

    private static void rows(DatabucketClient admin) {
        String data = "/api/bucket/" + PerfConfig.BUCKET;
        long existing = admin.postV2(data + "/get?limit=0", MAPPER.createObjectNode(), 200).path("total").asLong();
        System.out.println("Rows: " + existing + " in the bucket, " + PerfConfig.ROWS + " wanted");
        long start = System.currentTimeMillis();
        for (long n = existing + 1; n <= PerfConfig.ROWS; n += BATCH) {
            ArrayNode rows = MAPPER.createArrayNode();
            for (long i = n; i < n + BATCH && i <= PerfConfig.ROWS; i++)
                rows.addObject().set("properties", MAPPER.valueToTree(Orders.properties(i)));
            admin.postV2(data + "/multi", rows, 201);
            long done = Math.min(n + BATCH - 1, PerfConfig.ROWS);
            if (done % 20_000 == 0 || done == PerfConfig.ROWS)
                System.out.printf(java.util.Locale.ROOT, "  %,d rows (%d s)%n", done, (System.currentTimeMillis() - start) / 1000);
        }
        System.out.println("Ready.");
    }
}
