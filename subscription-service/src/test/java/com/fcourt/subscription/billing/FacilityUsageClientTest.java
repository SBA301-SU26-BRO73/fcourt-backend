package com.fcourt.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fcourt.subscription.billing.BillingDtos.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Usage counting from the owner's branch list, and the null fallback. */
class FacilityUsageClientTest {
    MockRestServiceServer server;
    FacilityUsageClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FacilityUsageClient(builder, "http://facility");
    }

    @Test
    void sumsBranchesAndCourtsOfTheOwnerUsingTheirToken() {
        server.expect(requestTo("http://facility/api/admin/branches"))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("[{\"id\":1,\"adminId\":7,\"courtCount\":4,\"name\":\"A\"},"
                        + "{\"id\":2,\"adminId\":7,\"courtCount\":5}]", MediaType.APPLICATION_JSON));

        assertThat(client.usageOf(7, "tok")).isEqualTo(new Usage(2, 9));
        server.verify();
    }

    @Test
    void ignoresBranchesOfOtherOwners() {
        server.expect(requestTo("http://facility/api/admin/branches"))
                .andRespond(withSuccess("[{\"id\":1,\"adminId\":7,\"courtCount\":4},"
                        + "{\"id\":2,\"adminId\":8,\"courtCount\":5}]", MediaType.APPLICATION_JSON));

        assertThat(client.usageOf(7, "tok")).isEqualTo(new Usage(1, 4));
    }

    @Test
    void ownerWithoutBranchesHasZeroUsage() {
        server.expect(requestTo("http://facility/api/admin/branches"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.usageOf(7, "tok")).isEqualTo(new Usage(0, 0));
    }

    @Test
    void facilityFailureGivesNull() {
        server.expect(requestTo("http://facility/api/admin/branches")).andRespond(withServerError());

        assertThat(client.usageOf(7, "tok")).isNull();
    }
}
