package com.grandvista.roomservice.controller;

import com.grandvista.roomservice.config.SecurityConfig;
import com.grandvista.roomservice.security.SecurityErrorWriter;
import com.grandvista.roomservice.service.HotelService;
import com.grandvista.roomservice.service.InventoryService;
import com.grandvista.roomservice.service.PricingRuleService;
import com.grandvista.roomservice.service.PricingService;
import com.grandvista.roomservice.service.RoomService;
import com.grandvista.roomservice.util.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {RoomController.class, HotelController.class, PricingRuleController.class,
        InternalHoldController.class},
        properties = "grandvista.internal.api-key=test-internal-key")
@Import({SecurityConfig.class, SecurityErrorWriter.class})
class RoomSecurityTest {

    private static final String ROOM_JSON = """
            {"hotelId":1,"roomNumber":"401","roomType":"SUITE","basePrice":9000,"capacity":3}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoomService roomService;
    @MockitoBean
    private InventoryService inventoryService;
    @MockitoBean
    private PricingService pricingService;
    @MockitoBean
    private HotelService hotelService;
    @MockitoBean
    private PricingRuleService pricingRuleService;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void roomBrowsingIsPublic() throws Exception {
        when(roomService.searchRooms(any())).thenReturn(List.of());
        mockMvc.perform(get("/rooms").param("city", "Chennai")).andExpect(status().isOk());
    }

    @Test
    void creatingRoomWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(ROOM_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void creatingRoomAsUserReturns403() throws Exception {
        mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(ROOM_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void creatingRoomAsAdminIsAllowed() throws Exception {
        mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON).content(ROOM_JSON)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidRoomPayloadReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/rooms").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hotelId":1,"roomNumber":"401","roomType":"SUITE","basePrice":-5,"capacity":0}""")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.basePrice").exists())
                .andExpect(jsonPath("$.validationErrors.capacity").exists());
    }

    @Test
    void adminListingIsForbiddenForUsers() throws Exception {
        mockMvc.perform(get("/rooms/all").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void pricingRulesRequireAdmin() throws Exception {
        mockMvc.perform(get("/pricing-rules").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalEndpointRejectsCallerWithoutServiceKeyEvenWithValidJwt() throws Exception {
        mockMvc.perform(post("/internal/holds/1/release")
                        .with(jwt().jwt(j -> j.subject("5")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Internal endpoint: trusted service credentials required"));
    }

    @Test
    void internalEndpointAcceptsServiceKeyPlusJwt() throws Exception {
        mockMvc.perform(post("/internal/holds/1/release")
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .with(jwt().jwt(j -> j.subject("5")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk());
    }

    @Test
    void trustedServiceWithKeyButNoTokenCanConfirmAndReleaseAsSystem() throws Exception {
        mockMvc.perform(post("/internal/holds/1/confirm").header("X-Internal-Api-Key", "test-internal-key"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/holds/1/release").header("X-Internal-Api-Key", "test-internal-key"))
                .andExpect(status().isOk());
        verify(inventoryService).confirmHold(1L, AuthenticatedUser.SYSTEM);
        verify(inventoryService).releaseHold(1L, AuthenticatedUser.SYSTEM);
    }

    @Test
    void internalEndpointsNeedTheKeyEvenWithoutAnyToken() throws Exception {
        mockMvc.perform(post("/internal/holds/1/release")).andExpect(status().isForbidden());
        mockMvc.perform(post("/internal/holds/1/release").header("X-Internal-Api-Key", "wrong"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidTokenIsStillRejectedEvenWithTheServiceKey() throws Exception {
        when(jwtDecoder.decode("forged")).thenThrow(new BadJwtException("bad signature"));

        mockMvc.perform(post("/internal/holds/1/release").header("X-Internal-Api-Key", "test-internal-key")
                        .header("Authorization", "Bearer forged"))
                .andExpect(status().isUnauthorized());
    }
}
