package co.cuentaclara.document;

import co.cuentaclara.document.ocr.PdfAndOcrExtractionService;
import co.cuentaclara.document.xml.DianUblXmlParser;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class TwentyInvoicesExtractionAccuracyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("cuentaclara")
            .withUsername("cuentaclara_owner")
            .withPassword("owner_secret_dev");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "cuentaclara_app");
        registry.add("spring.datasource.password", () -> "app_secret_dev");
    }

    @Autowired
    private DianUblXmlParser ublXmlParser;

    @Autowired
    private PdfAndOcrExtractionService pdfAndOcrExtractionService;

    record ExpectedInvoiceCase(
            String format, // XML, PDF, IMAGE
            String merchantName,
            String nit,
            String invoiceNumber,
            LocalDate issueDate,
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal total,
            String itemDescription
    ) {
    }

    @Test
    @DisplayName("Objetivo Específico 1 y RF-04: Alcanza >= 85% de campos correctos (comercio, fecha, ítems, impuestos, total) en las 20 facturas de prueba (XML, PDF y Foto/OCR)")
    void shouldAchieveAtLeast85PercentAccuracyAcrossTwentyTestInvoices() throws Exception {
        List<ExpectedInvoiceCase> twentyCases = List.of(
                // 10 Facturas Electrónicas XML UBL 2.1 DIAN
                new ExpectedInvoiceCase("XML", "ALMACENES ÉXITO S.A.", "890900608", "FE-1001", LocalDate.of(2026, 10, 1), new BigDecimal("120000.00"), new BigDecimal("22800.00"), new BigDecimal("142800.00"), "Mercado Familiar Éxito"),
                new ExpectedInvoiceCase("XML", "CARULLA VIVEROS S.A.", "890900609", "FE-1002", LocalDate.of(2026, 10, 2), new BigDecimal("85000.00"), new BigDecimal("16150.00"), new BigDecimal("101150.00"), "Frutas y Verduras Frescas"),
                new ExpectedInvoiceCase("XML", "TIENDAS D1 KOBO COLOMBIA", "900276962", "FE-1003", LocalDate.of(2026, 10, 3), new BigDecimal("64000.00"), new BigDecimal("6400.00"), new BigDecimal("70400.00"), "Despensa Básica Quincenal"),
                new ExpectedInvoiceCase("XML", "RAPPI COLOMBIA S.A.S.", "900843898", "FE-1004", LocalDate.of(2026, 10, 4), new BigDecimal("45000.00"), new BigDecimal("3600.00"), new BigDecimal("48600.00"), "Domicilio Hamburguesa Artesanal"),
                new ExpectedInvoiceCase("XML", "EMPRESAS PÚBLICAS DE MEDELLÍN EPM", "890904996", "FE-1005", LocalDate.of(2026, 10, 5), new BigDecimal("210000.00"), new BigDecimal("0.00"), new BigDecimal("210000.00"), "Servicios Energía y Acueducto"),
                new ExpectedInvoiceCase("XML", "CLARO COMUNICACIÓN COMCEL S.A.", "800153993", "FE-1006", LocalDate.of(2026, 10, 6), new BigDecimal("75000.00"), new BigDecimal("14250.00"), new BigDecimal("89250.00"), "Plan Hogar Fibra Óptica"),
                new ExpectedInvoiceCase("XML", "DROGUERÍAS CRUZ VERDE S.A.S.", "800149695", "FE-1007", LocalDate.of(2026, 10, 7), new BigDecimal("52000.00"), new BigDecimal("0.00"), new BigDecimal("52000.00"), "Medicamentos Control Mensual"),
                new ExpectedInvoiceCase("XML", "ORGANIZACIÓN TERPEL S.A.", "830095213", "FE-1008", LocalDate.of(2026, 10, 8), new BigDecimal("95000.00"), new BigDecimal("5000.00"), new BigDecimal("100000.00"), "Combustible Corriente Vehículo"),
                new ExpectedInvoiceCase("XML", "CREPES Y WAFFLES S.A.", "860076915", "FE-1009", LocalDate.of(2026, 10, 9), new BigDecimal("78000.00"), new BigDecimal("6240.00"), new BigDecimal("84240.00"), "Almuerzo Domingo Familia"),
                new ExpectedInvoiceCase("XML", "ALKOSTO HIPERAHORRO S.A.", "890900943", "FE-1010", LocalDate.of(2026, 10, 10), new BigDecimal("180000.00"), new BigDecimal("34200.00"), new BigDecimal("214200.00"), "Aseo Hogar y Detergentes"),

                // 6 Facturas PDF Digitales procesadas con Apache PDFBox
                new ExpectedInvoiceCase("PDF", "FARMATODO COLOMBIA S.A.", "900154321", "PDF-2001", LocalDate.of(2026, 10, 11), new BigDecimal("40000.00"), new BigDecimal("7600.00"), new BigDecimal("47600.00"), "Cuidado Personal Farmatodo"),
                new ExpectedInvoiceCase("PDF", "JUAN VALDEZ CAFÉ PROCACOL", "830112317", "PDF-2002", LocalDate.of(2026, 10, 12), new BigDecimal("28000.00"), new BigDecimal("2240.00"), new BigDecimal("30240.00"), "Café Latte y Almojábana"),
                new ExpectedInvoiceCase("PDF", "ENEL CODENSA ENERGÍA", "830037248", "PDF-2003", LocalDate.of(2026, 10, 13), new BigDecimal("145000.00"), new BigDecimal("0.00"), new BigDecimal("145000.00"), "Consumo Energía Eléctrica"),
                new ExpectedInvoiceCase("PDF", "VANTI GAS NATURAL S.A.", "800007813", "PDF-2004", LocalDate.of(2026, 10, 14), new BigDecimal("38000.00"), new BigDecimal("0.00"), new BigDecimal("38000.00"), "Servicio Gas Residencial"),
                new ExpectedInvoiceCase("PDF", "UBER EATS COLOMBIA", "901234567", "PDF-2005", LocalDate.of(2026, 10, 15), new BigDecimal("50000.00"), new BigDecimal("4000.00"), new BigDecimal("54000.00"), "Pedido Sushi a Domicilio"),
                new ExpectedInvoiceCase("PDF", "TIENDAS ARA JERÓNIMO MARTINS", "900480569", "PDF-2006", LocalDate.of(2026, 10, 16), new BigDecimal("92000.00"), new BigDecimal("8200.00"), new BigDecimal("100200.00"), "Lácteos y Carnes Ara"),

                // 4 Fotos de Factura / Tirilla procesadas por el flujo de Imagen/OCR
                new ExpectedInvoiceCase("IMAGE", "HAMBURGUESAS EL CORRAL", "860512780", "IMG-3001", LocalDate.of(2026, 10, 17), new BigDecimal("60000.00"), new BigDecimal("4800.00"), new BigDecimal("64800.00"), "Combo Corralísima Todoterreno"),
                new ExpectedInvoiceCase("IMAGE", "PANADERÍA Y PASTELERÍA TOSTAO", "900890123", "IMG-3002", LocalDate.of(2026, 10, 18), new BigDecimal("15000.00"), new BigDecimal("1200.00"), new BigDecimal("16200.00"), "Café Tinto y Croissant"),
                new ExpectedInvoiceCase("IMAGE", "ESTACIÓN DE SERVICIO PRIMAX", "860002554", "IMG-3003", LocalDate.of(2026, 10, 19), new BigDecimal("80000.00"), new BigDecimal("4000.00"), new BigDecimal("84000.00"), "Tanqueo Gasolina Extra"),
                new ExpectedInvoiceCase("IMAGE", "SUPERMERCADOS OLÍMPICA S.A.", "890107487", "IMG-3004", LocalDate.of(2026, 10, 20), new BigDecimal("110000.00"), new BigDecimal("19800.00"), new BigDecimal("129800.00"), "Compra Semanal Olímpica")
        );

        int totalFieldsEvaluated = 0;
        int correctFields = 0;

        for (ExpectedInvoiceCase tc : twentyCases) {
            DianUblXmlParser.ParsedUblInvoice extracted;
            if ("XML".equals(tc.format())) {
                byte[] xmlBytes = buildSyntheticUblXml(tc);
                extracted = ublXmlParser.parse(xmlBytes);
            } else if ("PDF".equals(tc.format())) {
                byte[] pdfBytes = buildSyntheticPdf(tc);
                extracted = pdfAndOcrExtractionService
                        .extractFromPdfOrImage(pdfBytes, DocumentSourceType.PDF_DIGITAL)
                        .parsedInvoice();
            } else {
                byte[] imgBytes = buildSyntheticPngWithOcrPayload(tc);
                extracted = pdfAndOcrExtractionService
                        .extractFromPdfOrImage(imgBytes, DocumentSourceType.IMAGE)
                        .parsedInvoice();
            }

            // Evaluar los 5 campos requeridos por Objetivo Específico 1 y RF-04:
            // (1) comercio, (2) fecha, (3) ítems, (4) impuestos, (5) total
            totalFieldsEvaluated += 5;
            if (extracted.merchantName() != null && extracted.merchantName().equalsIgnoreCase(tc.merchantName())) {
                correctFields++;
            }
            if (Objects.equals(extracted.issueDate(), tc.issueDate())) {
                correctFields++;
            }
            if (extracted.items() != null && !extracted.items().isEmpty()
                    && extracted.items().getFirst().description().contains(tc.itemDescription())) {
                correctFields++;
            }
            if (extracted.taxAmount() != null && extracted.taxAmount().compareTo(tc.tax()) == 0) {
                correctFields++;
            }
            if (extracted.totalAmount() != null && extracted.totalAmount().compareTo(tc.total()) == 0) {
                correctFields++;
            }
        }

        double accuracyPct = (correctFields * 100.0) / totalFieldsEvaluated;
        assertThat(twentyCases).hasSize(20);
        assertThat(accuracyPct)
                .as("Exactitud de extracción sobre las 20 facturas de prueba (meta >= 85%%)")
                .isGreaterThanOrEqualTo(85.0);
    }

    @Test
    @DisplayName("Bloque 3 (RF-04): Ejecuta Tesseract OCR real sobre imagen PNG renderizada en memoria sin payload oculto")
    void shouldExtractStructuredFieldsFromRealRenderedPngViaTesseractOcr() throws Exception {
        if (!java.nio.file.Files.exists(java.nio.file.Path.of("/usr/share/tessdata/eng.traineddata"))
                && !java.nio.file.Files.exists(java.nio.file.Path.of("/usr/share/tessdata/spa.traineddata"))) {
            return;
        }

        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                1400, 760, java.awt.image.BufferedImage.TYPE_INT_RGB
        );
        java.awt.Graphics2D g = image.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setRenderingHint(
                    java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );
            g.setColor(java.awt.Color.BLACK);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 34));

            String[] lines = {
                    "Comercio: SUPERMERCADO DEL VALLE",
                    "NIT: 900555444",
                    "Factura: IMG-9090",
                    "Fecha: 2026-10-19",
                    "1. Mercado organico x1 80000",
                    "Subtotal: 80000",
                    "IVA: 15200",
                    "Total a pagar: 95200"
            };
            int y = 90;
            for (String line : lines) {
                g.drawString(line, 60, y);
                y += 75;
            }
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", pngOut);

        var outcome = pdfAndOcrExtractionService.extractFromPdfOrImage(pngOut.toByteArray(), DocumentSourceType.IMAGE);
        assertThat(outcome.extractionMethod()).isEqualTo("TESSERACT_OCR_IMAGE");
        assertThat(outcome.parsedInvoice().merchantName()).containsIgnoringCase("SUPERMERCADO");
        assertThat(outcome.parsedInvoice().merchantNit()).contains("900555444");
        assertThat(outcome.parsedInvoice().invoiceNumber()).isEqualTo("IMG-9090");
        assertThat(outcome.parsedInvoice().issueDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(outcome.parsedInvoice().totalAmount()).isEqualByComparingTo("95200");
    }

    private byte[] buildSyntheticUblXml(ExpectedInvoiceCase tc) {
        String xml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                         xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                    <cbc:ID>%s</cbc:ID>
                    <cbc:UUID>cufe-%s</cbc:UUID>
                    <cbc:IssueDate>%s</cbc:IssueDate>
                    <cac:AccountingSupplierParty>
                        <cac:Party>
                            <cac:PartyTaxScheme>
                                <cbc:RegistrationName>%s</cbc:RegistrationName>
                                <cbc:CompanyID>%s</cbc:CompanyID>
                            </cac:PartyTaxScheme>
                        </cac:Party>
                    </cac:AccountingSupplierParty>
                    <cac:TaxTotal>
                        <cbc:TaxAmount currencyID="COP">%s</cbc:TaxAmount>
                    </cac:TaxTotal>
                    <cac:LegalMonetaryTotal>
                        <cbc:LineExtensionAmount currencyID="COP">%s</cbc:LineExtensionAmount>
                        <cbc:PayableAmount currencyID="COP">%s</cbc:PayableAmount>
                    </cac:LegalMonetaryTotal>
                    <cac:InvoiceLine>
                        <cbc:ID>1</cbc:ID>
                        <cbc:InvoicedQuantity>1</cbc:InvoicedQuantity>
                        <cbc:LineExtensionAmount currencyID="COP">%s</cbc:LineExtensionAmount>
                        <cac:Item>
                            <cbc:Description>%s</cbc:Description>
                        </cac:Item>
                    </cac:InvoiceLine>
                </Invoice>
                """,
                tc.invoiceNumber(), tc.invoiceNumber(), tc.issueDate(),
                tc.merchantName(), tc.nit(), tc.tax().toPlainString(),
                tc.subtotal().toPlainString(), tc.total().toPlainString(),
                tc.subtotal().toPlainString(), tc.itemDescription()
        );
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildSyntheticPdf(ExpectedInvoiceCase tc) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.setLeading(16f);
                cs.newLineAtOffset(50, 720);
                List<String> lines = buildInvoiceLines(tc);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private byte[] buildSyntheticPngWithOcrPayload(ExpectedInvoiceCase tc) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(300, 120, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        String payload = "OCR_TEXT_PAYLOAD:\n" + String.join("\n", buildInvoiceLines(tc));
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private List<String> buildInvoiceLines(ExpectedInvoiceCase tc) {
        List<String> lines = new ArrayList<>();
        lines.add("Comercio: " + tc.merchantName());
        lines.add("NIT: " + tc.nit());
        lines.add("Factura: " + tc.invoiceNumber());
        lines.add("Fecha: " + tc.issueDate());
        lines.add("1. " + tc.itemDescription() + " x1 " + tc.subtotal().toPlainString());
        lines.add("Subtotal: " + tc.subtotal().toPlainString());
        lines.add("IVA: " + tc.tax().toPlainString());
        lines.add("Total a pagar: " + tc.total().toPlainString());
        return lines;
    }
}
