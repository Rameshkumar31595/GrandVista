package com.grandvista.bookingservice.controller;

import com.grandvista.bookingservice.config.SecurityConfig;
import com.grandvista.bookingservice.security.SecurityErrorWriter;
import com.grandvista.bookingservice.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BookingController.class)
@Import({SecurityConfig.class, SecurityErrorWriter.class})
class BookingSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingService bookingService;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void bookingWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":10,"checkIn":"2030-09-20","checkOut":"2030-09-23","numberOfGuests":2}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        "Authentication required: provide a valid 'Authorization: Bearer <token>' header"));
    }

    @Test
    void userCannotListAllBookings() throws Exception {
        mockMvc.perform(get("/bookings").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void bookingRequestIsValidated() throws Exception {
        mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":10,"checkIn":"2000-01-01","numberOfGuests":0}""")
                        .with(jwt().jwt(j -> j.subject("5")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.checkIn").exists())
                .andExpect(jsonPath("$.validationErrors.checkOut").exists())
                .andExpect(jsonPath("$.validationErrors.numberOfGuests").exists());
        verify(bookingService, never()).createBooking(any(), any());
    }

    @Test
    void validUserBookingIsAccepted() throws Exception {
        mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId":10,"checkIn":"2030-09-20","checkOut":"2030-09-23","numberOfGuests":2}""")
                        .with(jwt().jwt(j -> j.subject("5")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isCreated());
    }
}
