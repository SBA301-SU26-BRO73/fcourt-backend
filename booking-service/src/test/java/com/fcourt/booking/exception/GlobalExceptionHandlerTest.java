package com.fcourt.booking.exception;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

    record Body(@NotBlank String name) { }

    @RestController
    static class Probe {
        @GetMapping("/probe/status")
        String status() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot already taken");
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/probe/denied")
        String denied() {
            throw new AccessDeniedException("no");
        }

        @PostMapping("/probe/body")
        String body(@Valid @RequestBody Body body) {
            return body.name();
        }
    }

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void responseStatusExceptionKeepsStatusAndReasonAsProblemDetail() throws Exception {
        mvc.perform(get("/probe/status")).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Slot already taken"))
                .andExpect(jsonPath("$.instance").value("/probe/status"));
    }

    @Test
    void validationFailureIsBadRequestWithFieldErrors() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request fields"))
                .andExpect(jsonPath("$.errors[0]").value("name: must not be blank"));
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed request body"));
    }

    @Test
    void unexpectedExceptionIsGenericInternalServerError() throws Exception {
        mvc.perform(get("/probe/boom")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Unexpected server error"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("secret internal detail"))));
    }

    @Test
    void securityExceptionsAreLeftToSpringSecurity() {
        ServletException thrown = assertThrows(ServletException.class, () -> mvc.perform(get("/probe/denied")));
        assertInstanceOf(AccessDeniedException.class, thrown.getCause());
    }
}
