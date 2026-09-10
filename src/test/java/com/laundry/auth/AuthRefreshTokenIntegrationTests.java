package com.laundry.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.user.Role;
import com.laundry.user.RoleRepository;
import com.laundry.user.User;
import com.laundry.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthRefreshTokenIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testUser;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        Role role = roleRepository.findByName("CUSTOMER")
            .orElseGet(() -> roleRepository.save(new Role("CUSTOMER")));

        testUser = userRepository.findByEmail("refresh-user@example.com")
            .orElseGet(() -> {
                User newUser = new User("Refresh User", "refresh-user@example.com", "+84909999999", passwordEncoder.encode("Password!1"));
                newUser.addRole(role);
                return userRepository.save(newUser);
            });
    }

    @Test
    void loginReturnsBothAccessAndRefreshToken() throws Exception {
        LoginRequest request = new LoginRequest("refresh-user@example.com", "Password!1");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void refreshTokenSuccessfullyRotatesTokens() throws Exception {
        LoginRequest loginRequest = new LoginRequest("refresh-user@example.com", "Password!1");
        String loginResponseContent = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        LoginResponse loginResponse = objectMapper.readValue(loginResponseContent, LoginResponse.class);
        String initialRefreshToken = loginResponse.refreshToken();

        // Perform refresh
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest(initialRefreshToken);
        String refreshResponseContent = mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andReturn().getResponse().getContentAsString();

        RefreshTokenResponse refreshResponse = objectMapper.readValue(refreshResponseContent, RefreshTokenResponse.class);

        // Verify token rotation: new refresh token is different from old refresh token
        assertThat(refreshResponse.refreshToken()).isNotEqualTo(initialRefreshToken);

        // Old refresh token is now revoked and cannot be used again
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshRequest)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidOrNonExistentRefreshToken() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest("non-existent-refresh-token");

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesActiveRefreshTokens() throws Exception {
        LoginRequest loginRequest = new LoginRequest("refresh-user@example.com", "Password!1");
        String loginResponseContent = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        LoginResponse loginResponse = objectMapper.readValue(loginResponseContent, LoginResponse.class);

        // Logout with bearer access token
        mockMvc.perform(post("/api/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginResponse.accessToken())
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        // Refresh token should now be revoked
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest(loginResponse.refreshToken());
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshRequest)))
            .andExpect(status().isUnauthorized());
    }
}
