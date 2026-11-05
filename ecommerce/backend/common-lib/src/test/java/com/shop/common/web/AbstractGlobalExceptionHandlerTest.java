package com.shop.common.web;

import com.shop.common.exception.ConflictException;
import com.shop.common.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AbstractGlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new TestExceptionHandler())
                .build();
    }

    @Test
    void resourceNotFound_returns404WithErrorBody() throws Exception {
        mockMvc.perform(get("/items/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Item not found with id '42'"))
                .andExpect(jsonPath("$.path").value("/items/42"));
    }

    @Test
    void conflict_returns409() throws Exception {
        mockMvc.perform(get("/items/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void invalidBody_returns400WithViolations() throws Exception {
        mockMvc.perform(post("/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations.length()").value(2));
    }

    @Test
    void malformedBody_returns400() throws Exception {
        mockMvc.perform(post("/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unexpectedException_returns500WithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/items/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void responseStatusException_keepsItsStatus() throws Exception {
        mockMvc.perform(get("/items/gone"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void missingCookie_returns400() throws Exception {
        mockMvc.perform(get("/items/with-cookie"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unacceptableMediaType_returns406() throws Exception {
        mockMvc.perform(get("/reports/json").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));
    }

    @Test
    void pageOffsetBeyondIntegerRange_returns400() throws Exception {
        mockMvc.perform(get("/items/huge-page"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Parameter 'page' has an invalid value"));
    }

    @Test
    void otherInvalidDataAccessUsage_staysAnInternalError() throws Exception {
        mockMvc.perform(get("/items/misuse"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @RestControllerAdvice
    static class TestExceptionHandler extends AbstractGlobalExceptionHandler {
    }

    @RestController
    static class TestController {

        @GetMapping("/items/gone")
        String gone() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "internal detail that must not leak");
        }

        @GetMapping("/items/with-cookie")
        String withCookie(@CookieValue("session") String session) {
            return session;
        }

        @GetMapping(value = "/reports/json", produces = MediaType.APPLICATION_JSON_VALUE)
        String json() {
            return "{}";
        }

        @GetMapping("/items/huge-page")
        String hugePage() {
            throw new InvalidDataAccessApiUsageException("Page offset exceeds Integer.MAX_VALUE (2147483647)");
        }

        @GetMapping("/items/misuse")
        String misuse() {
            throw new InvalidDataAccessApiUsageException("The given id must not be null");
        }

        @GetMapping("/items/conflict")
        String conflict() {
            throw new ConflictException("Item already exists");
        }

        @GetMapping("/items/boom")
        String boom() {
            throw new IllegalStateException("database password is hunter2");
        }

        @GetMapping("/items/{id}")
        String find(@PathVariable("id") Long id) {
            throw new ResourceNotFoundException("Item", "id", id);
        }

        @PostMapping("/items")
        String create(@Valid @RequestBody CreateItemRequest request) {
            return "created";
        }
    }

    record CreateItemRequest(@NotBlank String name, @Email String email) {
    }
}
