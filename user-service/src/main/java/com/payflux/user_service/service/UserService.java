package com.payflux.user_service.service;

import com.payflux.user_service.dto.UserDTO;
import com.payflux.user_service.entity.User;

import java.util.List;
import java.util.Optional;

public interface UserService {
    User createUser(User user);
    Optional<User> getUserById(Long id);
    List<User> getAllUsers();
    List<UserDTO> searchUsers(String query);
    Optional<User> updateUser(Long id, User user);
}
