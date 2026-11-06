package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class FullCourtCatalogTest {
    HttpServer server;
    FullCourtCatalog catalog;
    String branch="{\"id\":7,\"adminId\":10,\"status\":\"ACTIVE\",\"name\":\"Venue\"}";
    String courts="[{\"id\":1,\"branchId\":7,\"status\":\"ACTIVE\",\"name\":\"Court\"}]";
    Jwt jwt=Jwt.withTokenValue("fixture").header("alg","test").subject("10").claim("roles",List.of("ADMIN")).build();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/admin/branches/7", exchange->{
            boolean authorized="Bearer fixture".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] data=(exchange.getRequestURI().getPath().endsWith("/courts")?courts:branch).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(authorized?200:401,data.length);
            try(var body=exchange.getResponseBody()){body.write(data);}
        });
        server.start();
        catalog=new FullCourtCatalog(RestClient.builder(),mock(CounterDependencies.class),"http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void verifiedOwnerMayQuoteOnlyActiveCourtsInBranch() {
        assertThatCode(()->catalog.requireOwned(7,List.of(1L),jwt)).doesNotThrowAnyException();
        assertThatThrownBy(()->catalog.requireOwned(7,List.of(2L),jwt)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void foreignOwnerOrForeignBranchCannotSupplyOwnCourtIds() {
        branch="{\"id\":7,\"adminId\":11,\"status\":\"ACTIVE\"}";
        assertThatThrownBy(()->catalog.requireOwned(7,List.of(1L),jwt)).isInstanceOf(ResponseStatusException.class);
        branch="{\"id\":7,\"adminId\":10,\"status\":\"ACTIVE\"}";
        courts="[{\"id\":1,\"branchId\":8,\"status\":\"ACTIVE\"}]";
        assertThatThrownBy(()->catalog.requireOwned(7,List.of(1L),jwt)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void malformedAuthorityResponseFailsClosed() {
        branch="not-json";
        assertThatThrownBy(()->catalog.requireOwned(7,List.of(1L),jwt)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error->assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(503));
    }
}
