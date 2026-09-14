package com.grandvista.paymentservice.controller;

import com.grandvista.paymentservice.config.SecurityConfig;
import com.grandvista.paymentservice.security.SecurityErrorWriter;
import com.grandvista.paymentservice.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {PaymentController.class, InternalPaymentController.class},
        properties = "grandvista.internal.api-key=test-internal-key")
@Import({SecurityConfig.class, SecurityErrorWriter.class})
class PaymentSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void paymentHistoryRequiresToken() throws Exception {
        mockMvc.perform(get("/payments/my")).andExpect(status().isUnauthorized());
    }

    @Test
    void listingAllPaymentsRequiresAdmin() throws Exception {
        mockMvc.perform(get("/payments").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/payments").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void browserCannotCreatePaymentWithoutServiceKey() throws Exception {
        mockMvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":1,"amount":100,"method":"UPI"}""")
                        .with(jwt().jwt(j -> j.subject("7")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPaymentRequestIsRejected() throws Exception {
        mockMvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .content("""
                                {"bookingId":1,"amount":-5}""")
                        .with(jwt().jwt(j -> j.subject("7")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.amount").exists())
                .andExpect(jsonPath("$.validationErrors.method").exists());
    }
}
