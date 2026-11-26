package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.ValidatedFile;

public interface FileSecurityValidatorPort {

    ValidatedFile inspectAndValidate(String originalFilename, byte[] fileBytes, boolean isStatement);

    String computeSha256(byte[] content);
}
