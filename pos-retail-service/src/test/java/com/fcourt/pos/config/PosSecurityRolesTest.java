package com.fcourt.pos.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role matrix of the business filter chain. Uses a probe controller so no database or upstream is needed:
 * it proves which role reaches which path prefix (a SUPER_ADMIN token is refused on the staff catalog).
 */
@WebMvcTest(controllers = PosSecurityRolesTest.Probe.class, properties = {
        "pos.jwk-set-uri=http://localhost:1/jwks", "pos.issuer=test", "pos.audience=fcourt",
        "pos.settlement-token=", "eureka.client.enabled=false"})
@Import(PosSecurity.class)
class PosSecurityRolesTest {
    @RestController
    static class Probe {
        @GetMapping({"/api/staff/pos/products", "/api/admin/pos/products", "/api/super-admin/pos/products"})
        String ok() { return "ok"; }
    }

    @Autowired MockMvc mvc;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(String role) {
        return jwt().jwt(token -> token.subject("1").claim("roles", java.util.List.of(role)))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test void superAdminCannotUseStaffCatalog() throws Exception {
        mvc.perform(get("/api/staff/pos/products").with(as("SUPER_ADMIN"))).andExpect(status().isForbidden());
    }
    @Test void superAdminCannotUseOwnerList() throws Exception {
        mvc.perform(get("/api/admin/pos/products").with(as("SUPER_ADMIN"))).andExpect(status().isForbidden());
    }
    @Test void superAdminReachesAllBranchList() throws Exception {
        mvc.perform(get("/api/super-admin/pos/products").with(as("SUPER_ADMIN"))).andExpect(status().isOk());
    }
    @Test void staffAndAdminCannotUseSuperAdminList() throws Exception {
        mvc.perform(get("/api/super-admin/pos/products").with(as("STAFF"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/super-admin/pos/products").with(as("ADMIN"))).andExpect(status().isForbidden());
    }
    @Test void staffStillReachesCatalog() throws Exception {
        mvc.perform(get("/api/staff/pos/products").with(as("STAFF"))).andExpect(status().isOk());
    }
    @Test void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/super-admin/pos/products")).andExpect(status().isUnauthorized());
    }
}
