package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.FinancialProfile;
import java.util.Optional;
import java.util.UUID;

public interface FinancialProfileRepositoryPort {

    Optional<FinancialProfile> findByUserId(UUID userId);

    FinancialProfile save(FinancialProfile profile);
}
