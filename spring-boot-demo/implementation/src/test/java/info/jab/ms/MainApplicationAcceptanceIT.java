package info.jab.ms;

import info.jab.ms.common.PostgreSQLTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * MainApplicationAcceptanceIT - TestRestTemplate-based acceptance tests
 *
 * This class implements the acceptance test foundation for the Film Query API
 * using TestRestTemplate and TestContainers following Outside-in TDD strategy.
 *
 * Improvements applied:
 * - Fixed class name typo (MainApplicaitonAcceptanceIT -> MainApplicationAcceptanceIT)
 * - Added constants for magic numbers and strings
 * - Used modern Java features (var, Records, assertAll)
 * - Improved method organization with helper methods
 * - Enhanced readability and maintainability
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MainApplicationAcceptanceIT extends PostgreSQLTestBase {

    // Constants for better maintainability
    private static final String API_BASE_PATH = "/api/v1/films";
    private static final int EXPECTED_FILMS_STARTING_WITH_A = 46;
    private static final long PERFORMANCE_THRESHOLD_MS = 2000L;
    private static final String TEST_DATABASE_NAME = "testdb";
    private static final String TEST_USERNAME = "testuser";
    private static final String EXPECTED_FILMS_COUNT_WITH_A = "51";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    // Records for better data structure representation
    record ApiResponse(List<Map<String, Object>> films, Integer count, Map<String, Object> filter) {}
    record TestQueryParams(String startsWith, int expectedCount, String description) {}
    record PerformanceMetrics(long executionTime, int resultCount) {}

    /**
     * Basic setup test to verify Spring Boot Test with TestRestTemplate is working
     * and TestContainers PostgreSQL configuration is properly set up.
     */
    @Test
    @DisplayName("Should verify TestRestTemplate and PostgreSQL setup is working correctly")
    void testRestTemplateAndPostgreSQLSetupIsWorking() {
        assertAll("TestRestTemplate and PostgreSQL Setup",
            () -> assertThat(restTemplate).as("TestRestTemplate should be autowired").isNotNull(),
            () -> assertThat(port).as("Random port should be assigned").isGreaterThan(0),
            () -> validateBaseUrlConstruction(),
            () -> validatePostgreSQLContainer(),
            () -> validateDatabaseConnection(),
            () -> validateTestDataIsLoaded()
        );
    }

    @Test
    @DisplayName("Should retrieve exactly 46 films starting with 'A' with correct response structure")
    void shouldRetrieveFilmsStartingWithA() {
        // Given
        var queryParams = new TestQueryParams("A", EXPECTED_FILMS_STARTING_WITH_A, "films starting with A");

        // When
        var response = performFilmQuery(queryParams.startsWith());

        // Then
        assertAll("Films starting with A validation",
            () -> validateSuccessfulResponse(response),
            () -> validateApiResponseStructure(response, queryParams),
            () -> validateFilmDataIntegrity(response),
            () -> validateAllTitlesStartWith(response, queryParams.startsWith())
        );
    }

    @Test
    @DisplayName("Should complete film query within 2 seconds performance threshold")
    void shouldPerformQueryUnderTwoSeconds() {
        // Given
        var queryParams = new TestQueryParams("A", EXPECTED_FILMS_STARTING_WITH_A, "performance test");

        // When
        var performanceMetrics = measureQueryPerformance(queryParams);

        // Then
        assertAll("Performance validation",
            () -> assertThat(performanceMetrics.executionTime())
                .as("Query execution time should be under %d ms, but was %d ms",
                    PERFORMANCE_THRESHOLD_MS, performanceMetrics.executionTime())
                .isLessThan(PERFORMANCE_THRESHOLD_MS),
            () -> assertThat(performanceMetrics.resultCount())
                .as("Should return expected number of results for performance verification")
                .isEqualTo(EXPECTED_FILMS_STARTING_WITH_A)
        );

        System.out.printf("Film query performance: %d ms for %d results%n",
            performanceMetrics.executionTime(), performanceMetrics.resultCount());
    }

    @Test
    @DisplayName("Should handle empty results gracefully for non-existent film patterns")
    void shouldHandleEmptyResultsGracefully() {
        // Given
        var queryParams = new TestQueryParams("X", 0, "non-existent films");

        // When
        var response = performFilmQuery(queryParams.startsWith());

        // Then
        assertAll("Empty results validation",
            () -> validateSuccessfulResponse(response),
            () -> validateEmptyResponse(response, queryParams),
            () -> validateResponseStructureConsistency(response)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ABC", "@", "123"})
    @DisplayName("Should return HTTP 400 for invalid query parameter: {0}")
    void shouldHandleInvalidQueryParametersWithHttp400(String invalidParam) {
        // When
        var response = performFilmQuery(invalidParam);

        // Then
        assertThat(response.getStatusCode())
            .as("Parameter '%s' should return HTTP 400 Bad Request", invalidParam)
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Helper Methods for better code organization

    private ResponseEntity<Map> performFilmQuery(String startsWith) {
        var url = API_BASE_PATH + "?startsWith=" + startsWith;
        return restTemplate.getForEntity(url, Map.class);
    }

    private PerformanceMetrics measureQueryPerformance(TestQueryParams queryParams) {
        long startTime = System.currentTimeMillis();
        var response = performFilmQuery(queryParams.startsWith());
        long endTime = System.currentTimeMillis();

        long executionTime = endTime - startTime;
        var responseBody = response.getBody();
        Integer count = responseBody != null ? (Integer) responseBody.get("count") : 0;

        return new PerformanceMetrics(executionTime, count != null ? count : 0);
    }

    private void validateBaseUrlConstruction() {
        var baseUrl = "http://localhost:" + port;
        assertThat(baseUrl).contains("localhost");
    }

    private void validatePostgreSQLContainer() {
        assertAll("PostgreSQL Container validation",
            () -> assertThat(getPostgresContainer().isRunning())
                .as("PostgreSQL container should be running").isTrue(),
            () -> assertThat(getPostgresContainer().getDatabaseName())
                .as("Database name should be testdb").isEqualTo(TEST_DATABASE_NAME),
            () -> assertThat(getPostgresContainer().getUsername())
                .as("Username should be testuser").isEqualTo(TEST_USERNAME)
        );
    }

    private void validateDatabaseConnection() {
        var jdbcUrl = getPostgresContainer().getJdbcUrl();
        assertAll("Database connection validation",
            () -> assertThat(jdbcUrl).contains(TEST_DATABASE_NAME),
            () -> assertThat(jdbcUrl).startsWith("jdbc:postgresql://")
        );
    }

    private void validateTestDataIsLoaded() {
        try {
            var result = getPostgresContainer().execInContainer(
                "psql", "-U", TEST_USERNAME, "-d", TEST_DATABASE_NAME, "-c", "SELECT COUNT(*) FROM film;"
            );

            assertAll("Test data validation",
                () -> assertThat(result.getExitCode())
                    .as("Direct container query should succeed. Error: " + result.getStderr())
                    .isEqualTo(0),
                () -> assertThat(result.getStdout())
                    .as("Should have 51 films from the test data")
                    .contains(EXPECTED_FILMS_COUNT_WITH_A)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute query in container", e);
        }
    }

    private void validateSuccessfulResponse(ResponseEntity<Map> response) {
        assertAll("HTTP response validation",
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(response.getHeaders().getContentType().toString())
                .contains("application/json"),
            () -> assertThat(response.getBody()).isNotNull()
        );
    }

    private void validateApiResponseStructure(ResponseEntity<Map> response, TestQueryParams queryParams) {
        var responseBody = response.getBody();
        assertAll("API response structure",
            () -> assertThat(responseBody).containsKey("films"),
            () -> assertThat(responseBody).containsKey("count"),
            () -> assertThat(responseBody).containsKey("filter"),
            () -> assertThat((Integer) responseBody.get("count")).isEqualTo(queryParams.expectedCount()),
            () -> {
                @SuppressWarnings("unchecked")
                var filter = (Map<String, Object>) responseBody.get("filter");
                assertThat(filter).containsEntry("startsWith", queryParams.startsWith());
            }
        );
    }

    private void validateFilmDataIntegrity(ResponseEntity<Map> response) {
        @SuppressWarnings("unchecked")
        var films = (List<Map<String, Object>>) response.getBody().get("films");

        assertThat(films).hasSize(EXPECTED_FILMS_STARTING_WITH_A);

        films.forEach(film -> assertAll("Film data integrity",
            () -> assertThat(film).containsKey("film_id"),
            () -> assertThat(film).containsKey("title"),
            () -> assertThat(film.get("film_id")).isNotNull(),
            () -> assertThat(film.get("title")).isNotNull()
        ));
    }

    private void validateAllTitlesStartWith(ResponseEntity<Map> response, String expectedPrefix) {
        @SuppressWarnings("unchecked")
        var films = (List<Map<String, Object>>) response.getBody().get("films");

        films.forEach(film -> {
            var title = (String) film.get("title");
            assertThat(title).startsWithIgnoringCase(expectedPrefix);
        });
    }

    private void validateEmptyResponse(ResponseEntity<Map> response, TestQueryParams queryParams) {
        var responseBody = response.getBody();
        @SuppressWarnings("unchecked")
        var films = (List<Map<String, Object>>) responseBody.get("films");

        assertAll("Empty response validation",
            () -> assertThat(films).isEmpty(),
            () -> assertThat((Integer) responseBody.get("count")).isEqualTo(0),
            () -> {
                @SuppressWarnings("unchecked")
                var filter = (Map<String, Object>) responseBody.get("filter");
                assertThat(filter).containsEntry("startsWith", queryParams.startsWith());
            }
        );
    }

    private void validateResponseStructureConsistency(ResponseEntity<Map> response) {
        var responseBody = response.getBody();
        assertAll("Response structure consistency",
            () -> assertThat(responseBody.get("films")).isInstanceOf(List.class),
            () -> assertThat(responseBody.get("count")).isInstanceOf(Integer.class),
            () -> assertThat(responseBody.get("filter")).isInstanceOf(Map.class)
        );
    }
}
