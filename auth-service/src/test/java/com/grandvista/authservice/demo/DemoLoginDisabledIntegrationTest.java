package com.grandvista.authservice.demo;

import com.grandvista.authservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Whole auth service with NO demo property set: proves the secure default. */
@SpringBootTest(properties = {
        "grandvista.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYtYXV0aC10ZXN0",
        "spring.datasource.url=jdbc:h2:mem:demo-disabled;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "eureka.client.enabled=false"
})
@AutoConfigureMockMvc
class DemoLoginDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;

    @Test
    void demoModeIsOffByDefaultAndCreatesNoDemoAccounts() {
        assertThat(userRepository.findAll()).noneMatch(user -> user.getEmail().endsWith("@demo.grandvista.local"));
    }

    @Test
    void demoLoginIsForbiddenEvenWhenCalledDirectly() throws Exception {
        mockMvc.perform(post("/auth/demo/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"manager\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Demo login is disabled on this server"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void accountListIsForbidden() throws Exception {
        mockMvc.perform(get("/auth/demo/accounts")).andExpect(status().isForbidden());
    }
}
