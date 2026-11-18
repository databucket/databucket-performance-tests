package pl.databucket.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A small API client for what happens outside the measured load: signing in before the test, preparing the data.
 */
public final class DatabucketClient {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String token;

    private DatabucketClient(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    /** Signs in to the project of the given name (or without a project when null). */
    public static DatabucketClient signIn(String username, String password, String project) {
        JsonNode response = new DatabucketClient(null).call("POST", "/api/public/signin",
                MAPPER.createObjectNode().put("username", username).put("password", password), 200);
        JsonNode current = response.path("project");
        if (project == null || project.equals(current.path("name").asText(null)))
            return new DatabucketClient(response.path("token").asText());
        // several projects: sign in again to the one wanted
        for (JsonNode p : response.path("projects")) {
            if (project.equals(p.path("name").asText()))
                return new DatabucketClient(new DatabucketClient(null).call("POST", "/api/public/signin",
                        MAPPER.createObjectNode().put("username", username).put("password", password)
                                .put("projectId", p.path("id").asLong()), 200).path("token").asText());
        }
        throw new IllegalStateException("User " + username + " has no project '" + project + "'. Run ./gradlew prepareData first.");
    }

    public JsonNode get(String path) {
        return call("GET", path, null, 200);
    }

    public JsonNode post(String path, Object body, int expectedStatus) {
        return call("POST", path, body, expectedStatus);
    }

    /** POST of the data API version 2. */
    public JsonNode postV2(String path, Object body, int expectedStatus) {
        return call("POST", path, body, expectedStatus, "Databucket-Api-Version", "2");
    }

    public JsonNode call(String method, String path, Object body, int expectedStatus, String... headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(PerfConfig.BASE_URL + path))
                    .timeout(Duration.ofMinutes(2))
                    .header("Accept", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            if (body != null) request.header("Content-Type", "application/json");
            if (token != null) request.header("Authorization", "Bearer " + token);
            for (int i = 0; i + 1 < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (expectedStatus > 0 && response.statusCode() != expectedStatus)
                throw new IllegalStateException(method + " " + path + ": expected " + expectedStatus + ", got "
                        + response.statusCode() + ": " + response.body());
            return response.body().isEmpty() ? MAPPER.nullNode() : MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
