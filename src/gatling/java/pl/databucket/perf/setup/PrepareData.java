package pl.databucket.perf.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pl.databucket.perf.DatabucketClient;
import pl.databucket.perf.PerfConfig;
import pl.databucket.perf.TestUsers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static pl.databucket.perf.DatabucketClient.MAPPER;

/**
 * Prepares the data for the tests, idempotent ({@code ./gradlew prepareData -Prows=100000 -Ptesters=1000}): the
 * project, its ADMIN, the pool of tester accounts (ROBOT), the bucket (created when missing), users inserted until the
 * bucket has the given number of rows, and RESERVED_PERCENT of them reserved by the admin (not available to the tests).
 */
public final class PrepareData {

    private static final int BATCH = 1000;
    private static final int ROLE_ADMIN = 2;
    private static final int ROLE_ROBOT = 4;
    private static final int ROLE_AGENT = 5;
    private static final int THREADS = 8;

    public static void main(String[] args) throws Exception {
        System.out.println("Preparing: " + PerfConfig.describe());
        DatabucketClient superAdmin = DatabucketClient.signIn(PerfConfig.SUPER_USER, PerfConfig.SUPER_PASSWORD, null);

        long projectId = project(superAdmin);
        accounts(superAdmin, projectId);

        DatabucketClient admin = DatabucketClient.signIn(PerfConfig.ADMIN, PerfConfig.PASSWORD, PerfConfig.PROJECT);
        long bucketId = bucket(admin);
        rows(admin);
        tags(admin, bucketId);
        reserved(admin);
        System.out.println("Ready.");
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

    /** The admin and the testers that don't exist yet, created by a few threads. */
    private static void accounts(DatabucketClient superAdmin, long projectId) throws Exception {
        Set<String> existing = new HashSet<>();
        for (JsonNode user : superAdmin.get("/api/manage/users")) existing.add(user.path("username").asText());

        if (!existing.contains(PerfConfig.ADMIN)) {
            account(superAdmin, PerfConfig.ADMIN, ROLE_ADMIN, projectId);
            System.out.println("Admin " + PerfConfig.ADMIN + ": created");
        }

        List<String> missing = new ArrayList<>();
        for (int i = 1; i <= PerfConfig.TESTERS; i++)
            if (!existing.contains(PerfConfig.tester(i))) missing.add(PerfConfig.tester(i));
        System.out.println("Testers: " + (PerfConfig.TESTERS - missing.size()) + " of " + PerfConfig.TESTERS + " exist, creating " + missing.size());
        if (missing.isEmpty()) return;

        AtomicInteger done = new AtomicInteger();
        long start = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (String username : missing) {
                tasks.add(pool.submit(() -> {
                    account(superAdmin, username, ROLE_ROBOT, projectId);
                    int n = done.incrementAndGet();
                    if (n % 100 == 0 || n == missing.size())
                        System.out.printf(Locale.ROOT, "  %d testers (%d s)%n", n, (System.currentTimeMillis() - start) / 1000);
                }));
            }
            for (Future<?> task : tasks) task.get();
        } finally {
            pool.shutdown();
        }
    }

    /** A user with the given role in the project, with PASSWORD set (not a forced change any more). */
    private static void account(DatabucketClient superAdmin, String username, int role, long projectId) {
        ObjectNode user = MAPPER.createObjectNode().put("username", username).put("enabled", true)
                .put("description", "Performance tests");
        user.putArray("rolesIds").add(role);
        user.putArray("projectsIds").add(projectId);
        superAdmin.post("/api/manage/users", user, 201);
        // a new user has no usable password: a temporary one, then changed
        String temporary = "tmp-" + PerfConfig.PASSWORD;
        superAdmin.post("/api/manage/users/password/reset", MAPPER.createObjectNode()
                .put("username", username).put("password", temporary), 200);
        DatabucketClient first = DatabucketClient.signIn(username, temporary, null);
        first.post("/api/users/password/change", MAPPER.createObjectNode().put("username", username)
                .put("password", temporary).put("newPassword", PerfConfig.PASSWORD), 200);
    }

    private static long bucket(DatabucketClient admin) {
        for (JsonNode bucket : admin.get("/api/buckets")) {
            if (PerfConfig.BUCKET.equals(bucket.path("name").asText())) {
                System.out.println("Bucket " + PerfConfig.BUCKET + ": exists (history " + bucket.path("history").asBoolean() + ")");
                return bucket.path("id").asLong();
            }
        }
        ObjectNode bucket = MAPPER.createObjectNode().put("name", PerfConfig.BUCKET).put("history", PerfConfig.HISTORY)
                .put("protectedData", false).put("roleId", ROLE_AGENT);
        bucket.putObject("icon").put("name", "Storage");
        long id = admin.post("/api/buckets", bucket, 201).path("id").asLong();
        System.out.println("Bucket " + PerfConfig.BUCKET + ": created (history " + PerfConfig.HISTORY + ")");
        return id;
    }

    /**
     * Tags perf-tag-01 ... (TAGS of them) and perf-created (rows created by the tests with -Pfilter=tags); the rows of
     * prepareData without a tag get one by the last digit of their group (spread evenly: 10 % per digit), by the API:
     * one update by rules per digit.
     */
    private static void tags(DatabucketClient admin, long bucketId) {
        Map<String, Long> existing = new HashMap<>();
        for (JsonNode tag : admin.get("/api/tags")) existing.put(tag.path("name").asText(), tag.path("id").asLong());
        List<String> wanted = new ArrayList<>();
        for (int i = 1; i <= PerfConfig.TAGS; i++) wanted.add(PerfConfig.tagName(i));
        wanted.add(PerfConfig.CREATED_TAG);
        for (String name : wanted) {
            if (existing.containsKey(name)) continue;
            ObjectNode tag = MAPPER.createObjectNode().put("name", name).put("description", "Performance tests");
            tag.putArray("bucketsIds").add(bucketId);
            existing.put(name, admin.post("/api/tags", tag, 201).path("id").asLong());
        }
        System.out.println("Tags: " + PerfConfig.TAGS + " + " + PerfConfig.CREATED_TAG);

        String data = "/api/bucket/" + PerfConfig.BUCKET;
        long start = System.currentTimeMillis();
        for (int digit = 0; digit <= 9; digit++) {
            String tag = PerfConfig.tagName(digit % PerfConfig.TAGS + 1);
            ObjectNode body = rules(Arrays.asList("tagId", "==", null), Arrays.asList("$.origin", "==", null),
                    List.of("$.group", "like", "%" + digit));
            body.put("tagId", existing.get(tag));
            JsonNode result = admin.call("PUT", data, body, 200, "Databucket-Api-Version", "2");
            String message = result.path("message").asText();
            if (!message.startsWith("Modified 0") && !message.isEmpty())
                System.out.printf(Locale.ROOT, "  group *%d -> %s: %s (%d s)%n", digit, tag, message, (System.currentTimeMillis() - start) / 1000);
        }
    }

    /** The rows of prepareData (no origin): inserted until there are ROWS of them. */
    private static void rows(DatabucketClient admin) {
        String data = "/api/bucket/" + PerfConfig.BUCKET;
        long existing = count(admin, rules(Arrays.asList("$.origin", "==", null)));
        System.out.println("Rows: " + existing + " in the bucket, " + PerfConfig.ROWS + " wanted");
        long start = System.currentTimeMillis();
        for (long n = existing + 1; n <= PerfConfig.ROWS; n += BATCH) {
            ArrayNode rows = MAPPER.createArrayNode();
            for (long i = n; i < n + BATCH && i <= PerfConfig.ROWS; i++)
                rows.addObject().set("properties", MAPPER.valueToTree(TestUsers.properties(i)));
            admin.postV2(data + "/multi", rows, 201);
            long done = Math.min(n + BATCH - 1, PerfConfig.ROWS);
            if (done % 20_000 == 0 || done == PerfConfig.ROWS)
                System.out.printf(Locale.ROOT, "  %d rows (%d s)%n", done, (System.currentTimeMillis() - start) / 1000);
        }
    }

    /** RESERVED_PERCENT of the rows of prepareData reserved by the admin, random ones. */
    private static void reserved(DatabucketClient admin) {
        long wanted = (long) PerfConfig.ROWS * PerfConfig.RESERVED_PERCENT / 100;
        long existing = count(admin, rules(Arrays.asList("$.origin", "==", null), List.of("reserved", "==", true)));
        System.out.println("Reserved at the start: " + existing + " rows, " + wanted + " wanted (" + PerfConfig.RESERVED_PERCENT + " %)");
        String path = "/api/bucket/" + PerfConfig.BUCKET + "/reserve?sort=random&limit=";
        long start = System.currentTimeMillis();
        while (existing < wanted) {
            long limit = Math.min(BATCH, wanted - existing);
            JsonNode response = admin.postV2(path + limit, rules(Arrays.asList("$.origin", "==", null)), 200);
            int reserved = response.path("data").size();
            if (reserved == 0) {
                System.out.println("  no free rows left to reserve");
                break;
            }
            existing += reserved;
            if (existing % 20_000 < BATCH || existing >= wanted)
                System.out.printf(Locale.ROOT, "  %d reserved (%d s)%n", existing, (System.currentTimeMillis() - start) / 1000);
        }
    }

    private static long count(DatabucketClient admin, ObjectNode rules) {
        return admin.postV2("/api/bucket/" + PerfConfig.BUCKET + "/get?limit=0", rules, 200).path("total").asLong();
    }

    @SafeVarargs
    private static ObjectNode rules(List<Object>... rules) {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("rules", MAPPER.valueToTree(List.of(rules)));
        return body;
    }
}
