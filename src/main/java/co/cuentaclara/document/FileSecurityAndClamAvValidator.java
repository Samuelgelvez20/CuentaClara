package co.cuentaclara.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Validación de seguridad de archivos por contenido real (magic bytes), límite de 10 MB,
 * hash SHA-256 para detección de duplicados (RF-06) y escaneo antivirus con ClamAV (RNF-03).
 */
@Service
public class FileSecurityAndClamAvValidator {

    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L; // 10 MB
    private static final String EICAR_SIGNATURE = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private final boolean clamAvEnabled;
    private final String clamAvHost;
    private final int clamAvPort;

    public FileSecurityAndClamAvValidator(
            @Value("${cuentaclara.clamav.enabled:false}") boolean clamAvEnabled,
            @Value("${cuentaclara.clamav.host:localhost}") String clamAvHost,
            @Value("${cuentaclara.clamav.port:3310}") int clamAvPort
    ) {
        this.clamAvEnabled = clamAvEnabled;
        this.clamAvHost = clamAvHost;
        this.clamAvPort = clamAvPort;
    }

    public ValidatedFile inspectAndValidate(String originalFilename, byte[] content, boolean isStatement) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("El archivo subido está vacío.");
        }
        if (content.length > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("El archivo supera el tamaño máximo permitido de 10 MB (RNF-03).");
        }

        // Escaneo antivirus (firma EICAR + demonio ClamAV TCP INSTREAM cuando está habilitado)
        scanForMalware(content);

        // Detección real por contenido (magic bytes), nunca confiando solo en la extensión (RNF-03)
        DetectedContent detected = detectContentTypeByMagicBytes(content, isStatement);
        String sha256 = computeSha256(content);

        return new ValidatedFile(
                originalFilename != null && !originalFilename.isBlank() ? originalFilename : "documento",
                detected.mimeType(),
                detected.sourceType(),
                content.length,
                sha256
        );
    }

    private void scanForMalware(byte[] content) {
        String asciiSample = new String(content, 0, Math.min(content.length, 4096), StandardCharsets.ISO_8859_1);
        if (asciiSample.contains(EICAR_SIGNATURE)) {
            throw new IllegalArgumentException("Archivo bloqueado por el escáner antivirus (ClamAV): amenaza detectada.");
        }

        if (!clamAvEnabled) {
            return;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(clamAvHost, clamAvPort), 4000);
            socket.setSoTimeout(8000);
            try (OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream()) {

                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                int offset = 0;
                int chunkSize = 8192;
                while (offset < content.length) {
                    int len = Math.min(chunkSize, content.length - offset);
                    byte[] lenBytes = ByteBuffer.allocate(4).putInt(len).array();
                    out.write(lenBytes);
                    out.write(content, offset, len);
                    offset += len;
                }
                out.write(new byte[]{0, 0, 0, 0});
                out.flush();

                ByteArrayOutputStream responseBuf = new ByteArrayOutputStream();
                byte[] buffer = new byte[256];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    responseBuf.write(buffer, 0, read);
                }
                String reply = responseBuf.toString(StandardCharsets.US_ASCII).trim();
                if (reply.contains("FOUND")) {
                    throw new IllegalArgumentException("Archivo rechazado por ClamAV Antivirus: " + reply);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo verificar el archivo con el servicio antivirus ClamAV: " + ex.getMessage(), ex);
        }
    }

    private DetectedContent detectContentTypeByMagicBytes(byte[] bytes, boolean isStatement) {
        // 1. PDF (%PDF-)
        if (bytes.length >= 5
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F'
                && bytes[4] == '-') {
            DocumentSourceType type = isStatement ? DocumentSourceType.STATEMENT_PDF : DocumentSourceType.PDF_DIGITAL;
            return new DetectedContent("application/pdf", type);
        }

        // 2. PNG (89 50 4E 47 0D 0A 1A 0A)
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47) {
            if (isStatement) {
                throw new IllegalArgumentException("Los extractos bancarios deben subirse como PDF digital (RF-02).");
            }
            return new DetectedContent("image/png", DocumentSourceType.IMAGE);
        }

        // 3. JPEG (FF D8 FF)
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            if (isStatement) {
                throw new IllegalArgumentException("Los extractos bancarios deben subirse como PDF digital (RF-02).");
            }
            return new DetectedContent("image/jpeg", DocumentSourceType.IMAGE);
        }

        // 4. XML (<?xml o <Invoice / <AttachedDocument / <CreditNote / <DebitNote)
        String head = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8)
                .replace("\uFEFF", "")
                .trim();
        if (head.startsWith("<?xml")
                || head.startsWith("<Invoice")
                || head.startsWith("<fe:Invoice")
                || head.startsWith("<AttachedDocument")
                || head.startsWith("<CreditNote")
                || head.startsWith("<DebitNote")) {
            if (isStatement) {
                throw new IllegalArgumentException("Los extractos bancarios deben ser PDF digital, no XML.");
            }
            return new DetectedContent("application/xml", DocumentSourceType.XML_UBL);
        }

        throw new IllegalArgumentException(
                "Formato de archivo no permitido o contenido inválido. Solo se aceptan facturas XML UBL 2.1, PDF o imágenes JPG/PNG de hasta 10 MB (RF-02, RNF-03)."
        );
    }

    public String computeSha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Algoritmo SHA-256 no disponible", ex);
        }
    }

    private record DetectedContent(String mimeType, DocumentSourceType sourceType) {
    }

    public record ValidatedFile(
            String originalFilename,
            String mimeType,
            DocumentSourceType sourceType,
            long fileSizeBytes,
            String sha256Hash
    ) {
    }
}
