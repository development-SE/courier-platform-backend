package kz.courier.authservice.controller;

import kz.courier.auth.v1.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthRestController {

    private final AuthServiceGrpc.AuthServiceBlockingStub grpcStub;

    @PostMapping("/register")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest req) {
        return grpcStub.register(req);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return grpcStub.login(req);
    }

    @PostMapping("/refresh")
    public RefreshTokenResponse refresh(@RequestBody RefreshTokenRequest req) {
        return grpcStub.refreshToken(req);
    }

    @GetMapping("/verify")
    public kz.courier.common.v1.Response verify(@RequestParam String token) {
        return grpcStub.verifyEmail(VerifyEmailRequest.newBuilder().setToken(token).build());
    }
}
