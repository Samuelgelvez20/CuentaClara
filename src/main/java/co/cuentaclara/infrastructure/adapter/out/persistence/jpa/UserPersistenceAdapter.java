package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import co.cuentaclara.domain.model.User;
import co.cuentaclara.domain.port.out.UserRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UserPersistenceAdapter implements UserRepositoryPort {

    private final UserSpringDataRepository repository;

    public UserPersistenceAdapter(UserSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return repository.findById(id).map(UserPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<User> findByEmailIgnoreCase(String email) {
        return repository.findByEmailIgnoreCase(email).map(UserPersistenceAdapter::toDomain);
    }

    @Override
    public boolean existsByEmailIgnoreCase(String email) {
        return repository.existsByEmailIgnoreCase(email);
    }

    @Override
    public User save(User user) {
        UserJpaEntity saved = repository.save(toEntity(user));
        return toDomain(saved);
    }

    @Override
    public void deleteById(UUID id) {
        repository.deleteById(id);
    }

    static User toDomain(UserJpaEntity entity) {
        User domain = new User();
        domain.setId(entity.getId());
        domain.setEmail(entity.getEmail());
        domain.setPasswordHash(entity.getPasswordHash());
        domain.setFullName(entity.getFullName());
        domain.setAiConsentAccepted(entity.isAiConsentAccepted());
        domain.setAiConsentAt(entity.getAiConsentAt());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }

    static UserJpaEntity toEntity(User domain) {
        UserJpaEntity entity = new UserJpaEntity();
        entity.setId(domain.getId());
        entity.setEmail(domain.getEmail());
        entity.setPasswordHash(domain.getPasswordHash());
        entity.setFullName(domain.getFullName());
        entity.setAiConsentAccepted(domain.isAiConsentAccepted());
        entity.setAiConsentAt(domain.getAiConsentAt());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }
}
