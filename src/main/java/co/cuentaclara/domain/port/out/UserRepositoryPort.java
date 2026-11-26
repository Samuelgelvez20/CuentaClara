package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.User;
import java.util.Optional;
import java.util.UUID;

public interface UserRepositoryPort {

    Optional<User> findById(UUID id);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    User save(User user);

    void deleteById(UUID id);
}
