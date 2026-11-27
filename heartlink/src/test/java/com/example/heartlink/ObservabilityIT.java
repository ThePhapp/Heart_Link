package com.example.heartlink;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityIT {
    @LocalServerPort int appPort;
    @LocalManagementPort int managementPort;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void healthIsAvailableOnlyOnTheInternalManagementListenerAndHidesDetails() throws Exception {
        var health = get(managementUrl("/actuator/health"));

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(health.body()).doesNotContain("components", "details");
        assertThat(get(appUrl("/actuator/health")).statusCode()).isEqualTo(404);
    }

    @Test
    void requestIdIsReturnedAndNotAddedAsAMetricTag() throws Exception {
        String requestId = UUID.randomUUID().toString();
        var request = HttpRequest.newBuilder(URI.create(appUrl("/api/v1/observability-probe")))
                .header("X-Request-ID", requestId).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("X-Request-ID")).contains(requestId);
        assertThat(response.statusCode()).isEqualTo(404);

        var metrics = get(managementUrl("/actuator/metrics/http.server.requests"));
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("http.server.requests");
        assertThat(metrics.body()).doesNotContain(requestId, "userId", "email", "authorization", "requestId", "token", "content");
    }

    @Test
    void hikariPoolMetricsAreAvailableOnTheManagementListener() throws Exception {
        var metrics = get(managementUrl("/actuator/metrics/hikaricp.connections.active"));

        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("hikaricp.connections.active");
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String appUrl(String path) { return "http://127.0.0.1:" + appPort + path; }
    private String managementUrl(String path) { return "http://127.0.0.1:" + managementPort + path; }
}
