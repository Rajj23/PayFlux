package com.payflux.user_service.controller;

import com.payflux.user_service.entity.User;
import com.payflux.user_service.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false) // Ignore security filters for unit test
public class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private com.payflux.user_service.util.JWTUtil jwtUtil;

    @Test
    void testGetUserById() throws Exception {
        User user = new User();
        user.setId(5L);
        user.setName("John Doe");
        user.setEmail("john@test.com");
        user.setRole("ROLE_USER");

        when(userService.getUserById(5L)).thenReturn(java.util.Optional.of(user));

        mockMvc.perform(get("/api/users/5")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("John Doe"))
                .andExpect(jsonPath("$.id").value(5L));
    }
}
