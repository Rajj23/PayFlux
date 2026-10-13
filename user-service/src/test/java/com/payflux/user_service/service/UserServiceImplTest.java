package com.payflux.user_service.service;

import com.payflux.user_service.client.WalletClient;
import com.payflux.user_service.dto.CreateWalletRequest;
import com.payflux.user_service.entity.User;
import com.payflux.user_service.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalletClient walletClient;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void testCreateUser_Success() {
        User user = new User();
        user.setName("Test User");
        user.setEmail("test@test.com");

        User savedUser = new User();
        savedUser.setId(5L);
        savedUser.setName("Test User");
        savedUser.setEmail("test@test.com");

        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        // We don't need to mock walletClient if it returns void, Mockito handles it.

        User result = userService.createUser(user);

        assertNotNull(result);
        assertEquals(5L, result.getId());
        verify(userRepository, times(1)).save(user);

        // Verify Feign Client Call
        verify(walletClient, times(1)).createWallet(any(CreateWalletRequest.class));
    }

    @Test
    void testGetUserById_Found() {
        User user = new User();
        user.setId(10L);
        user.setEmail("find@test.com");

        when(userRepository.findById(10L)).thenReturn(Optional.of(user));

        User result = userService.getUserById(10L).orElse(null);

        assertNotNull(result);
        assertEquals(10L, result.getId());
    }

    @Test
    void testGetUserById_NotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<User> result = userService.getUserById(99L);
        assertTrue(result.isEmpty());
    }
}
