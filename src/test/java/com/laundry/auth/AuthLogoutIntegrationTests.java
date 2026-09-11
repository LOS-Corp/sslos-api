package com.laundry.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.laundry.auth.security.JwtService;
import com.laundry.user.entity.Role;
import com.laundry.user.entity.User;
import com.laundry.user.repository.RoleRepository;
import com.laundry.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class AuthLogoutIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private JwtService jwtService;

    private String validToken;

    @BeforeEach
    void setUp() {
        Role role = roleRepository.findByName("CUSTOMER")
            .orElseGet(() -> roleRepository.save(new Role("CUSTOMER")));

        User user = userRepository.findByEmail("logout-test@example.com")
            .orElseGet(() -> {
                User newUser = new User("Logout User", "logout-test@example.com", "+84901234568", "hash");
                newUser.addRole(role);
                return userRepository.save(newUser);
            });

        validToken = jwtService.createToken(user);
    }

    @Test
    void rejectsLogoutWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsLogoutWithValidBearerToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Logged out successfully"));
    }
}
