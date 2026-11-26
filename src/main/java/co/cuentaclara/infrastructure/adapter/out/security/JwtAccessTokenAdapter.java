package co.cuentaclara.infrastructure.adapter.out.security;

import co.cuentaclara.domain.model.AuthenticatedUser;
import co.cuentaclara.domain.port.out.AccessTokenPort;
import co.cuentaclara.infrastructure.security.jwt.JwtTokenService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JwtAccessTokenAdapter implements AccessTokenPort {

    private final JwtTokenService jwtTokenService;

    public JwtAccessTokenAdapter(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    public String generateToken(UUID userId, String email, String fullName) {
        return jwtTokenService.generateToken(userId, email, fullName);
    }

    @Override
    public Optional<AuthenticatedUser> parseAndValidate(String token) {
        return jwtTokenService.parseAndValidate(token)
                .map(p -> new AuthenticatedUser(p.userId(), p.email(), p.fullName()));
    }
}
