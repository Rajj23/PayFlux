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
        if(userRepository.findByEmail(request.getEmail()).isPresent()){
            return ResponseEntity
                    .badRequest()
                    .body("User Already Exists");
        }

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setRole("ROLE_USER");
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        User savedUser = userService.createUser(user);

        return ResponseEntity.ok("User registered successfully" + savedUser.getId());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request){
        Optional<User> userOpt = userRepository.findByEmail(request.getEmail());

        if(userOpt.isEmpty()){
            return ResponseEntity.status(401).body("User not found");
        }

        User user = userOpt.get();

        if(!passwordEncoder.matches(request.getPassword(),user.getPassword())){
            return ResponseEntity.status(401).body("Invalid credentials");
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("role",user.getRole());
        claims.put("userId", String.valueOf(user.getId()));

        String token = jwtUtil.generateToken(claims, user.getEmail());

        return ResponseEntity.ok(new JwtResponse(token));

    }
}
