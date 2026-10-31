package com.fcourt.auth.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** Writes RFC 7807 bodies from security filters, where no controller advice applies. */
public final class ProblemResponses {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProblemResponses() {
    }

    public static void write(HttpServletRequest request, HttpServletResponse response,
                             HttpStatus status, String detail) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JSON.writeValue(response.getOutputStream(), body);
    }
}
