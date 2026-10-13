package com.payflux.user_service.controller;

import com.payflux.user_service.dto.JwtResponse;
import com.payflux.user_service.dto.LoginRequest;
import com.payflux.user_service.dto.SignupRequest;
import com.payflux.user_service.entity.User;
import com.payflux.user_service.repository.UserRepository;
import com.payflux.user_service.service.UserService;
import com.payflux.user_service.util.JWTUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JWTUtil jwtUtil;
    private final UserService userService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JWTUtil jwtUtil, UserService userService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest request){
        String email = request.getEmail() != null ? request.getEmail().trim().toLowerCase() : "";
        if(userRepository.findByEmail(email).isPresent()){
            Map<String, String> err = new HashMap<>();
            err.put("message", "User already exists with this email");
            return ResponseEntity.badRequest().body(err);
        }

        User user = new User();
        user.setName(request.getName() != null ? request.getName().trim() : "");
        user.setEmail(email);
        user.setRole("ROLE_USER");
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        User savedUser = userService.createUser(user);

        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "User registered successfully");
        resp.put("userId", savedUser.getId());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request){
        String email = request.getEmail() != null ? request.getEmail().trim().toLowerCase() : "";
        Optional<User> userOpt = userRepository.findByEmail(email);

        if(userOpt.isEmpty()){
            Map<String, String> err = new HashMap<>();
            err.put("message", "User not found. Please check your email or create an account.");
            return ResponseEntity.status(401).body(err);
        }

        User user = userOpt.get();

        if(!passwordEncoder.matches(request.getPassword(),user.getPassword())){
            Map<String, String> err = new HashMap<>();
            err.put("message", "Invalid credentials. Please check your password.");
            return ResponseEntity.status(401).body(err);
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("role",user.getRole());
        claims.put("userId", String.valueOf(user.getId()));

        String token = jwtUtil.generateToken(claims, user.getEmail());

        Map<String, Object> resp = new HashMap<>();
        resp.put("token", token);
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("name", user.getName());
        userInfo.put("email", user.getEmail());
        resp.put("user", userInfo);

        return ResponseEntity.ok(resp);
    }
}
