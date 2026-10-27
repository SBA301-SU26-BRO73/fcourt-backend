package com.fcourt.discovery;

import com.fcourt.discovery.api.BranchSearchRequest;
import com.fcourt.discovery.messaging.BranchUpdatedEvent;
import com.fcourt.discovery.messaging.BranchUpdatedEvent.CourtSnapshot;
import com.fcourt.discovery.persistence.BranchProjection;
import com.fcourt.discovery.persistence.BranchSearchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "debug=false")
@EmbeddedKafka(partitions = 1, topics = {"facility.branch-updated.v1", "facility.branch-updated.v1.DLT"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class DiscoveryIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().setPort(0).start();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired BranchProjection projection;
    @Autowired com.fcourt.discovery.application.BranchSearchService searchService;
    @Autowired BranchSearchRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.kafka.test.EmbeddedKafkaBroker broker;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE branch_read_model CASCADE");
        searchService.projectionChanged(new com.fcourt.discovery.persistence.BranchProjectionChanged(0));
    }

    static BranchUpdatedEvent snapshot(long id, long version, boolean active, boolean deleted) {
        return new BranchUpdatedEvent(id, version, "C\u1ea7u l\u00f4ng Sunrise", "123 Nguy\u1ec5n Tr\u00e3i",
                "H\u1ed3 Ch\u00ed Minh", "Qu\u1eadn 1", 10.0, 106.0, active, deleted,
                List.of(new CourtSnapshot(1L, 1L, new BigDecimal("100000"), new BigDecimal("200000")),
                        new CourtSnapshot(2L, 2L, new BigDecimal("300000"), new BigDecimal("400000"))));
    }

    static BranchSearchRequest request(String query, Long type, String min, String max,
                                       Double lat, Double lon, Integer page, Integer size) {
        return new BranchSearchRequest(query, null, null, type,
                min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max),
                lat, lon, page, size);
    }

    @Test
    void migratesAndSearchesVietnameseWithoutJwt() {
        projection.apply(snapshot(1, 1, true, false));
        var response = http.getForEntity(java.net.URI.create("/api/public/branches?query=nguyen%20trai"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("Sunrise", "100000", "400000", "\"courtCount\":2");
        var page = repository.search(new BranchSearchRequest(null, "ho chi minh", "quan 1", null,
                null, null, null, null, null, null));
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void fuzzySearchFindsMisspelledWordAndEscapesWildcards() {
        projection.apply(snapshot(1, 1, true, false));
        assertThat(repository.search(request("sunrize", null, null, null, null, null, null, null)).content())
                .extracting(item -> item.id()).containsExactly(1L);
        assertThat(repository.search(request("cau long sunrize", null, null, null, null, null, null, null)).content())
                .extracting(item -> item.id()).containsExactly(1L);
        assertThat(repository.search(request("sunrize absent", null, null, null, null, null, null, null)).content()).isEmpty();
        assertThat(repository.search(request("%_!", null, null, null, null, null, null, null)).content()).isEmpty();
    }

    @Test
    void invalidatesCachedSearchOnlyAfterProjectionCommits() {
        projection.apply(snapshot(1, 1, true, false));
        var request = request(null, null, null, null, null, null, null, null);
        assertThat(searchService.search(request).totalElements()).isEqualTo(1);
        projection.apply(snapshot(1, 2, false, false));
        assertThat(searchService.search(request).totalElements()).isZero();
    }

    @Test
    void filtersPriceAndTypeOnSameCourtAndReturnsWholeBranchAggregates() {
        projection.apply(snapshot(1, 1, true, false));
        assertThat(repository.search(request(null, 1L, "250000", null, null, null, null, null)).content()).isEmpty();
        var result = repository.search(request(null, 1L, "150000", "180000", null, null, null, null));
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().courtCount()).isEqualTo(2);
        assertThat(result.content().getFirst().maxPrice()).isEqualByComparingTo("400000");
    }

    @Test
    void ordersByHaversineAndKeepsUnknownCoordinatesLast() {
        projection.apply(snapshot(2, 1, true, false));
        projection.apply(new BranchUpdatedEvent(1L, 1L, "Far", "Address", "City", null,
                11.0, 106.0, true, false, List.of()));
        projection.apply(new BranchUpdatedEvent(3L, 1L, "Unknown", "Address", "City", null,
                null, null, true, false, List.of()));
        var result = repository.search(request(null, null, null, null, 10.0, 106.0, 0, 3));
        assertThat(result.content()).extracting(item -> item.id()).containsExactly(2L, 1L, 3L);
        assertThat(result.content().get(0).distanceKm()).isCloseTo(0, within(0.0001));
        assertThat(result.content().get(1).distanceKm()).isCloseTo(111.195, within(0.01));
        assertThat(result.content().get(2).distanceKm()).isNull();
    }

    @Test
    void ignoresDuplicateAndOutOfOrderEventsAndPreservesDeletionVersion() {
        projection.apply(snapshot(1, 2, true, false));
        projection.apply(snapshot(1, 2, false, false));
        projection.apply(snapshot(1, 1, false, false));
        assertThat(repository.search(request(null, null, null, null, null, null, null, null)).totalElements()).isEqualTo(1);
        projection.apply(snapshot(1, 3, true, true));
        projection.apply(snapshot(1, 2, true, false));
        assertThat(repository.search(request(null, null, null, null, null, null, null, null)).totalElements()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM court_read_model", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM branch_read_model WHERE id=1", Long.class)).isEqualTo(3);
    }

    @Test
    void replacesCourtsAndRollsBackInvalidSnapshotAtomically() {
        projection.apply(snapshot(1, 1, true, false));
        var bad = new BranchUpdatedEvent(1L, 2L, "Changed", "Address", "City", null,
                null, null, true, false, List.of(new CourtSnapshot(3L, 1L,
                new BigDecimal("200"), new BigDecimal("100"))));
        assertThatThrownBy(() -> projection.apply(bad)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT version FROM branch_read_model WHERE id=1", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM court_read_model", Long.class)).isEqualTo(2);
        projection.apply(new BranchUpdatedEvent(1L, 2L, "Empty", "Address", "City", null,
                null, null, true, false, List.of()));
        var item = repository.search(request(null, null, null, null, null, null, null, null)).content().getFirst();
        assertThat(item.courtCount()).isZero();
        assertThat(item.minPrice()).isNull();
    }

    @Test
    void paginatesDeterministicallyAndExcludesInactiveBranches() {
        projection.apply(snapshot(2, 1, true, false));
        projection.apply(snapshot(1, 1, true, false));
        projection.apply(snapshot(3, 1, false, false));
        var result = repository.search(request(null, null, null, null, null, null, 1, 1));
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.content()).extracting(item -> item.id()).containsExactly(2L);
        assertThat(repository.search(request(null, null, null, null, null, null, 5, 1)).content()).isEmpty();
    }

    @Test
    void rejectsInvalidPublicParameters() {
        for (String query : List.of("page=-1", "size=101", "latitude=91&longitude=0",
                "latitude=10", "minPrice=200&maxPrice=100", "courtTypeId=0", "page=abc", "minPrice=1.001")) {
            assertThat(http.getForEntity("/api/public/branches?" + query, String.class).getStatusCode().value())
                    .as(query).isEqualTo(400);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "discovery.benchmark", matches = "true")
    void measuresHttpLatencyOnTenThousandBranches() {
        jdbc.execute("""
                INSERT INTO branch_read_model
                    (id, version, name, address, city, search_text, city_key, active, latitude, longitude)
                SELECT n, 1, 'Branch ' || n, 'Street ' || n, 'City',
                    'branch ' || n || ' street ' || n, 'city', true, 10 + n * 0.00001, 106
                FROM generate_series(1, 10000) n
                """);
        jdbc.execute("""
                INSERT INTO court_read_model (branch_id, id, court_type_id, min_price, max_price)
                SELECT n, c, c, 100000, 300000 FROM generate_series(1, 10000) n
                CROSS JOIN generate_series(1, 5) c
                """);
        jdbc.execute("ANALYZE branch_read_model");
        jdbc.execute("ANALYZE court_read_model");
        for (String query : List.of("", "?query=brach", "?query=branc", "?city=City&courtTypeId=1&minPrice=150000",
                "?latitude=10&longitude=106")) {
            String url = "/api/public/branches" + query;
            long coldStart = System.nanoTime();
            assertThat(http.getForEntity(url, String.class).getStatusCode().value()).isEqualTo(200);
            System.out.printf("COLD %s %.2fms%n", url, (System.nanoTime() - coldStart) / 1e6);
            for (int i = 0; i < 20; i++) http.getForEntity(url, String.class);
            long[] nanos = new long[100];
            for (int i = 0; i < nanos.length; i++) {
                long start = System.nanoTime();
                assertThat(http.getForEntity(url, String.class).getStatusCode().value()).isEqualTo(200);
                nanos[i] = System.nanoTime() - start;
            }
            java.util.Arrays.sort(nanos);
            System.out.printf("LATENCY %s p50=%.2fms p95=%.2fms max=%.2fms%n",
                    url, nanos[49] / 1e6, nanos[94] / 1e6, nanos[99] / 1e6);
            assertThat(nanos[94] / 1e6).as("HTTP p95 for " + url).isLessThan(80);
        }
    }

    @Test
    void consumesKafkaSnapshotAndRecoversAfterPoisonMessage() throws Exception {
        var props = org.springframework.kafka.test.utils.KafkaTestUtils.consumerProps("dlt-test", "false", broker);
        try (var consumer = new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, "facility.branch-updated.v1.DLT");
            kafka.send("facility.branch-updated.v1", "99", "{invalid").get();
            var deadLetter = org.springframework.kafka.test.utils.KafkaTestUtils.getSingleRecord(consumer,
                    "facility.branch-updated.v1.DLT", Duration.ofSeconds(30));
            assertThat(deadLetter.value()).isEqualTo("{invalid");
            assertThat(deadLetter.key()).isEqualTo("99");
        }
        kafka.send("facility.branch-updated.v1", "99", mapper.writeValueAsString(snapshot(99, 1, true, false))).get();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM branch_read_model WHERE id=99", Long.class)).isEqualTo(1));
    }
}
