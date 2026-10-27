package com.grandvista.authservice.demo;

import com.grandvista.authservice.entity.Role;
import com.grandvista.authservice.entity.User;
import com.grandvista.authservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Whole auth service on in-memory H2 with demo mode ON. */
@SpringBootTest(properties = {
        "grandvista.demo.enabled=true",
        "grandvista.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYtYXV0aC10ZXN0",
        "spring.datasource.url=jdbc:h2:mem:demo-enabled;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "eureka.client.enabled=false"
})
@AutoConfigureMockMvc
class DemoLoginEnabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtDecoder jwtDecoder;
    @Autowired
    private DemoAccountInitializer initializer;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void listsTheThreeFixedAccountsWithConfigurableNamesAndNoSecrets() throws Exception {
        mockMvc.perform(get("/auth/demo/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value("ramesh"))
                .andExpect(jsonPath("$[0].displayName").value("Ramesh"))
                .andExpect(jsonPath("$[0].role").value("ROLE_USER"))
                .andExpect(jsonPath("$[1].displayName").value("Demo Guest 2"))
                .andExpect(jsonPath("$[1].role").value("ROLE_USER"))
                .andExpect(jsonPath("$[2].displayName").value("GrandVista Manager"))
                .andExpect(jsonPath("$[2].role").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    void guestDemoLoginIssuesAUserRoleJwt() throws Exception {
        JsonNode body = demoLogin("ramesh");

        assertThat(body.at("/user/role").asString()).isEqualTo("ROLE_USER");
        assertThat(body.at("/user/name").asString()).isEqualTo("Ramesh");
        Jwt jwt = jwtDecoder.decode(body.get("accessToken").asString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_USER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("grandvista-auth-service");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void administratorDemoLoginIssuesAnAdminRoleJwt() throws Exception {
        JsonNode body = demoLogin("manager");

        Jwt jwt = jwtDecoder.decode(body.get("accessToken").asString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_ADMIN");
        assertThat(body.at("/user/name").asString()).isEqualTo("GrandVista Manager");
    }

    @Test
    void secondGuestIsASeparateUser() throws Exception {
        long first = demoLogin("ramesh").at("/user/id").asLong();
        long second = demoLogin("guest2").at("/user/id").asLong();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void unknownOrMalformedIdentitiesAreRejected() throws Exception {
        for (String id : new String[] {"admin", "RAMESH", "demo.ramesh@demo.grandvista.local", "1", "../manager", " "}) {
            mockMvc.perform(post("/auth/demo/login").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of("accountId", id))))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(400, 401));
        }
        mockMvc.perform(post("/auth/demo/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void demoLoginCannotSignInARealUserByEmailOrId() throws Exception {
        userRepository.save(new User("Real Person", "real.person@example.com", passwordEncoder.encode("Secret123"),
                Role.ROLE_USER));

        mockMvc.perform(post("/auth/demo/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"real.person@example.com\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestTokenKeepsGuestRestrictionsAndAdminTokenKeepsAdminAccess() throws Exception {
        String guest = demoLogin("ramesh").get("accessToken").asString();
        String manager = demoLogin("manager").get("accessToken").asString();

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + guest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ROLE_USER"));
        mockMvc.perform(get("/auth/users").header("Authorization", "Bearer " + guest))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/auth/users").header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk());
    }

    @Test
    void demoAccountsCannotBeUsedWithThePasswordLogin() throws Exception {
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"demo.manager@demo.grandvista.local\",\"password\":\"Password123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void normalLoginAndRegistrationStillWork() throws Exception {
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Asha\",\"email\":\"asha.normal@example.com\",\"password\":\"Secret123\"}"))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"asha.normal@example.com\",\"password\":\"Secret123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        assertThat(jwtDecoder.decode(body.get("accessToken").asString()).getClaimAsStringList("roles"))
                .containsExactly("ROLE_USER");

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"asha.normal@example.com\",\"password\":\"WrongPass1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicRegistrationCannotClaimTheReservedDemoDomain() throws Exception {
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mallory\",\"email\":\"demo.other@demo.grandvista.local\",\"password\":\"Secret123\"}"))
                .andExpect(status().isConflict());
        assertThat(userRepository.findByEmail("demo.other@demo.grandvista.local")).isEmpty();
    }

    @Test
    void repeatedStartupsDoNotDuplicateOrRewriteDemoAccounts() {
        long before = userRepository.count();
        String hashBefore = userRepository.findByEmail("demo.manager@demo.grandvista.local").orElseThrow().getPassword();

        initializer.run(new DefaultApplicationArguments());
        initializer.run(new DefaultApplicationArguments());

        assertThat(userRepository.count()).isEqualTo(before);
        assertThat(userRepository.findByEmail("demo.manager@demo.grandvista.local").orElseThrow().getPassword())
                .isEqualTo(hashBefore);
    }

    @Test
    void demoLoginIsRefusedIfAnAdminChangedTheAccountRole() throws Exception {
        User guest2 = userRepository.findByEmail("demo.guest2@demo.grandvista.local").orElseThrow();
        guest2.setRole(Role.ROLE_ADMIN);
        userRepository.save(guest2);
        try {
            mockMvc.perform(post("/auth/demo/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accountId\":\"guest2\"}"))
                    .andExpect(status().isUnauthorized());
        } finally {
            guest2.setRole(Role.ROLE_USER);
            userRepository.save(guest2);
        }
    }

    private JsonNode demoLogin(String accountId) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/demo/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
