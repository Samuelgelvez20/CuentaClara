package co.cuentaclara.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialProfileRepository extends JpaRepository<FinancialProfile, UUID> {

    Optional<FinancialProfile> findByUserId(UUID userId);
}
