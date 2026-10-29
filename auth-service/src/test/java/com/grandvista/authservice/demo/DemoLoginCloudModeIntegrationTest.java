package com.grandvista.authservice.demo;

import com.grandvista.authservice.entity.Role;
import com.grandvista.authservice.entity.User;
import com.grandvista.authservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public-deployment settings (what the cloud profile sets): demo mode ON, reviewer access code required,
 * demo administrator restricted. Whole auth service on in-memory H2. Every test uses its own client address
 * (X-Forwarded-For) so the per-client failure counters do not interfere.
 */
@SpringBootTest(properties = {
        "grandvista.demo.enabled=true",
        "grandvista.demo.access.required=true",
        "grandvista.demo.access.code=" + DemoLoginCloudModeIntegrationTest.CODE,
        "grandvista.demo.access.restrict-demo-admin=true",
        "grandvista.demo.access.max-failures-total=1000",
        "grandvista.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYtYXV0aC10ZXN0",
        "spring.datasource.url=jdbc:h2:mem:demo-cloud;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "eureka.client.enabled=false"
})
@AutoConfigureMockMvc
class DemoLoginCloudModeIntegrationTest {

    static final String CODE = "review-2026-Orchid-Lantern";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtDecoder jwtDecoder;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void withoutTheAccessCodeNoAccountListAndNoSessionAreGiven() throws Exception {
        String client = newClient();
        mockMvc.perform(get("/auth/demo/accounts").header("X-Forwarded-For", client))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Reviewer access code required"))
                .andExpect(jsonPath("$[0]").doesNotExist());
        for (String id : new String[] {"ramesh", "guest2", "manager"}) {
            mockMvc.perform(post("/auth/demo/login").header("X-Forwarded-For", client)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"" + id + "\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.accessToken").doesNotExist());
        }
    }

    @Test
    void aWrongAccessCodeIsRejected() throws Exception {
        mockMvc.perform(post("/auth/demo/login").header("X-Forwarded-For", newClient())
                        .header(DemoAccessGuard.HEADER, "review-2026-orchid-lantern")   // case matters
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"manager\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Reviewer access code not accepted"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void withTheAccessCodeAllThreeAccountsSignInWithTheirFixedRoles() throws Exception {
        String client = newClient();
        mockMvc.perform(get("/auth/demo/accounts").header("X-Forwarded-For", client).header(DemoAccessGuard.HEADER, CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].displayName").value("Ramesh"))
                .andExpect(jsonPath("$[1].displayName").value("Demo Guest 2"))
                .andExpect(jsonPath("$[2].displayName").value("GrandVista Manager"));

        assertThat(roles(demoLogin("ramesh", client))).isEqualTo("ROLE_USER");
        assertThat(roles(demoLogin("guest2", client))).isEqualTo("ROLE_USER");
        assertThat(roles(demoLogin("manager", client))).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void repeatedWrongCodesAreRateLimitedEvenIfTheRightCodeFollows() throws Exception {
        String client = newClient();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/auth/demo/accounts").header("X-Forwarded-For", client)
                            .header(DemoAccessGuard.HEADER, "guess-number-" + i))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/auth/demo/login").header("X-Forwarded-For", client).header(DemoAccessGuard.HEADER, CODE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"manager\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        // another client is not affected
        demoLogin("ramesh", newClient());
    }

    @Test
    void guestRestrictionsStayAndTheDemoAdministratorCannotMintAdministrators() throws Exception {
        String client = newClient();
        String guest = demoLogin("ramesh", client).get("accessToken").asString();
        String manager = demoLogin("manager", client).get("accessToken").asString();

        mockMvc.perform(get("/auth/users").header("Authorization", "Bearer " + guest)).andExpect(status().isForbidden());
        mockMvc.perform(get("/auth/users").header("Authorization", "Bearer " + manager)).andExpect(status().isOk());

        // a guest can never change roles at all (including its own)
        long guestId = userRepository.findByEmail("demo.ramesh@demo.grandvista.local").orElseThrow().getId();
        mockMvc.perform(patch("/auth/users/" + guestId + "/role").header("Authorization", "Bearer " + guest)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isForbidden());

        // the shared demo administrator cannot promote anybody (e.g. a self-registered visitor) ...
        User visitor = userRepository.save(new User("Visitor", "visitor-" + UUID.randomUUID() + "@example.com",
                passwordEncoder.encode("Secret123"), Role.ROLE_USER));
        mockMvc.perform(patch("/auth/users/" + visitor.getId() + "/role").header("Authorization", "Bearer " + manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isForbidden());
        // ... nor demote a real administrator
        User realAdmin = userRepository.save(new User("Cloud Admin", "admin-" + UUID.randomUUID() + "@example.com",
                passwordEncoder.encode("Secret123"), Role.ROLE_ADMIN));
        mockMvc.perform(patch("/auth/users/" + realAdmin.getId() + "/role").header("Authorization", "Bearer " + manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ROLE_USER\"}"))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(visitor.getId()).orElseThrow().getRole()).isEqualTo(Role.ROLE_USER);
        assertThat(userRepository.findById(realAdmin.getId()).orElseThrow().getRole()).isEqualTo(Role.ROLE_ADMIN);
    }

    @Test
    void normalRegistrationAndLoginNeedNoAccessCode() throws Exception {
        String email = "cloud.user." + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cloud User\",\"email\":\"" + email + "\",\"password\":\"Secret123\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ROLE_USER"));
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"WrongPass1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void demoAccountsStillCannotUseThePasswordLogin() throws Exception {
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"demo.manager@demo.grandvista.local\",\"password\":\"" + CODE + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode demoLogin(String accountId, String client) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/demo/login").header("X-Forwarded-For", client)
                        .header(DemoAccessGuard.HEADER, CODE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"" + accountId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String roles(JsonNode loginBody) {
        return String.join(",", jwtDecoder.decode(loginBody.get("accessToken").asString()).getClaimAsStringList("roles"));
    }

    private static String newClient() {
        return "203.0.113." + (int) (Math.random() * 250 + 1) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
