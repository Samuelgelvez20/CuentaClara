package co.cuentaclara.chat;

import co.cuentaclara.document.DocumentIngestionService;
import co.cuentaclara.profile.BudgetBlock;
import co.cuentaclara.profile.FinancialProfileService;
import co.cuentaclara.profile.PaymentFrequency;
import co.cuentaclara.security.rls.RlsDataSourceConfig;
import co.cuentaclara.transaction.TransactionService;
import co.cuentaclara.user.User;
import co.cuentaclara.user.UserRepository;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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

@SpringBootTest(properties = "cuentaclara.chat.gemini-enabled=false")
@Testcontainers
class ThirtyQuestionsRagEvaluationTest {

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
    private UserRepository userRepository;

    @Autowired
    private FinancialProfileService profileService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private NaturalLanguageChatService chatService;

    record EvaluationQuestion(
            String question,
            boolean shouldHaveEvidence,
            String expectedSubstringInAnswer,
            String expectedCitationPrefix
    ) {
    }

    @Test
    @DisplayName("Objetivo Específico 4, RF-12 y RNF-09: Evaluación con 30 preguntas sobre el historial y documentos del usuario logrando >= 80% de respuestas correctas con cita verificable")
    void shouldAchieveAtLeast80PercentAccuracyWithValidCitationsAcross30Questions() throws Exception {
        User user = userRepository.save(new User(
                "evaluacion30@cuentaclara.co",
                "hash",
                "Usuario Evaluación RAG",
                true
        ));
        UUID userId = user.getId();

        // Poblar el historial confirmado y los documentos indexados del usuario bajo RLS
        RlsDataSourceConfig.runAsUser(userId, () -> {
            try {
                profileService.saveOrUpdateQuestionnaire(
                        userId,
                        new FinancialProfileService.QuestionnaireRequest(
                                new BigDecimal("4000000.00"),
                                PaymentFrequency.MONTHLY,
                                "30",
                                50, 30, 20,
                                "Ahorrar e invertir",
                                BigDecimal.ZERO,
                                List.of()
                        )
                );

                // 1. Factura XML Éxito (FE-5001, CUFE cufe-exito-5001, $178.500, IVA $28.500, ítem Café Molido Gourmet)
                ingestAndConfirmXml(userId, "FE-5001", "cufe-exito-5001", "2026-10-05", "ALMACENES ÉXITO S.A.", "890900608",
                        "150000.00", "28500.00", "178500.00", "Café Molido Gourmet 500g", "Alimentación", BudgetBlock.NEEDS);

                // 2. Factura XML Carulla (FE-5002, CUFE cufe-carulla-5002, $95.000, IVA $15.000, ítem Queso Maduro)
                ingestAndConfirmXml(userId, "FE-5002", "cufe-carulla-5002", "2026-10-08", "CARULLA S.A.", "890900609",
                        "80000.00", "15000.00", "95000.00", "Queso Maduro Holandés", "Alimentación", BudgetBlock.NEEDS);

                // 3. Factura XML Rappi (FE-5003, CUFE cufe-rappi-5003, $64.800, IVA $4.800, ítem Hamburguesa Artesanal)
                ingestAndConfirmXml(userId, "FE-5003", "cufe-rappi-5003", "2026-10-12", "RAPPI COLOMBIA S.A.S.", "900843898",
                        "60000.00", "4800.00", "64800.00", "Hamburguesa Artesanal Doble", "Domicilios y Restaurantes", BudgetBlock.WANTS);

                // 4. Factura XML Uber Eats (FE-5004, CUFE cufe-ubereats-5004, $54.000, IVA $4.000, ítem Rollos de Sushi)
                ingestAndConfirmXml(userId, "FE-5004", "cufe-ubereats-5004", "2026-10-15", "UBER EATS COLOMBIA", "901234567",
                        "50000.00", "4000.00", "54000.00", "Rollos de Sushi Salmón", "Domicilios y Restaurantes", BudgetBlock.WANTS);

                // 5. Factura XML EPM (FE-5005, CUFE cufe-epm-5005, $210.000, IVA $0, Servicios)
                ingestAndConfirmXml(userId, "FE-5005", "cufe-epm-5005", "2026-10-16", "EMPRESAS PÚBLICAS DE MEDELLÍN EPM", "890904996",
                        "210000.00", "0.00", "210000.00", "Energía Eléctrica y Acueducto", "Servicios", BudgetBlock.NEEDS);

                // 6. Factura XML Claro (FE-5006, CUFE cufe-claro-5006, $89.250, IVA $14.250, Servicios)
                ingestAndConfirmXml(userId, "FE-5006", "cufe-claro-5006", "2026-10-17", "CLARO COMUNICACIÓN COMCEL S.A.", "800153993",
                        "75000.00", "14250.00", "89250.00", "Internet Fibra Óptica 500 Megas", "Servicios", BudgetBlock.NEEDS);

                // 7. Factura XML Cruz Verde (FE-5007, CUFE cufe-cruzverde-5007, $52.000, Salud)
                ingestAndConfirmXml(userId, "FE-5007", "cufe-cruzverde-5007", "2026-10-19", "DROGUERÍAS CRUZ VERDE S.A.S.", "800149695",
                        "52000.00", "0.00", "52000.00", "Jarabe Antialérgico Loratadina", "Salud", BudgetBlock.NEEDS);

                // 8. Factura XML Terpel (FE-5008, CUFE cufe-terpel-5008, $100.000, Transporte)
                ingestAndConfirmXml(userId, "FE-5008", "cufe-terpel-5008", "2026-10-21", "ORGANIZACIÓN TERPEL S.A.", "830095213",
                        "95000.00", "5000.00", "100000.00", "Gasolina Corriente 6 Galones", "Transporte", BudgetBlock.NEEDS);

                // 9. Gasto Manual de Arriendo ($1.200.000, Vivienda)
                transactionService.registerManualExpense(
                        userId,
                        new TransactionService.ManualExpenseRequest(
                                "Inmobiliaria Los Robles",
                                LocalDate.of(2026, 10, 3),
                                new BigDecimal("1200000.00"),
                                "Vivienda",
                                BudgetBlock.NEEDS,
                                "Arriendo apartamento octubre"
                        ),
                        null, null
                );

                // 10. Extracto Bancario PDF digital de 2 páginas (citas por página: PÁG:1 y PÁG:2)
                byte[] statementPdf = buildTwoPageBankStatementPdf();
                var statementDoc = ingestionService.uploadAndProcessDocument(
                        userId,
                        "extracto_bancolombia_octubre.pdf",
                        statementPdf,
                        true,
                        false
                );
                transactionService.confirmExtractedDocument(
                        userId,
                        statementDoc.document().getId(),
                        new TransactionService.ConfirmDocumentRequest(
                                "Bancolombia Extracto Octubre", null, null, null,
                                LocalDate.of(2026, 10, 31),
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                                "Extracto Bancario", BudgetBlock.NEEDS, null, null
                        )
                );
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        // 30 preguntas de evaluación (numéricas SQL, híbridas por comercio/NIT/factura, por ítem, por página de extracto y sin evidencia)
        List<EvaluationQuestion> thirtyQuestions = List.of(
                new EvaluationQuestion("1. ¿Cuánto gasté en domicilios en octubre?", true, "118,800", "XML:FE-5003"),
                new EvaluationQuestion("2. ¿Cuánto gasté en alimentación en octubre?", true, "273,500", "XML:FE-5001"),
                new EvaluationQuestion("3. ¿Cuánto gasté en servicios en octubre?", true, "299,250", "XML:FE-5005"),
                new EvaluationQuestion("4. ¿Cuánto gasté en salud en octubre?", true, "52,000", "XML:FE-5007"),
                new EvaluationQuestion("5. ¿Cuánto gasté en transporte en octubre?", true, "100,000", "XML:FE-5008"),
                new EvaluationQuestion("6. ¿Cuánto pagué de vivienda o arriendo en octubre?", true, "1,200,000", "MANUAL:"),
                new EvaluationQuestion("7. ¿Cuánto gasté en Almacenes Éxito?", true, "178,500", "XML:FE-5001"),
                new EvaluationQuestion("8. ¿Cuál fue el gasto en Carulla?", true, "95,000", "XML:FE-5002"),
                new EvaluationQuestion("9. ¿Cuánto gasté en Rappi?", true, "64,800", "XML:FE-5003"),
                new EvaluationQuestion("10. ¿Cuánto gasté en Uber Eats?", true, "54,000", "XML:FE-5004"),
                new EvaluationQuestion("11. ¿Cuánto pagué a EPM?", true, "210,000", "XML:FE-5005"),
                new EvaluationQuestion("12. ¿Cuánto pagué en Claro?", true, "89,250", "XML:FE-5006"),
                new EvaluationQuestion("13. ¿Cuánto gasté en Cruz Verde?", true, "52,000", "XML:FE-5007"),
                new EvaluationQuestion("14. ¿Cuánto gasté en Terpel?", true, "100,000", "XML:FE-5008"),
                new EvaluationQuestion("15. ¿Cuánto fue el IVA pagado en Almacenes Éxito?", true, "28,500", "XML:FE-5001"),
                new EvaluationQuestion("16. ¿Cuánto fue el impuesto IVA en la factura de Claro?", true, "14,250", "XML:FE-5006"),
                new EvaluationQuestion("17. ¿Qué compré en la factura FE-5001?", true, "178,500", "XML:FE-5001"),
                new EvaluationQuestion("18. ¿Qué factura corresponde al NIT 890900608?", true, "ALMACENES ÉXITO", "XML:FE-5001"),
                new EvaluationQuestion("19. ¿Qué comercio tiene el NIT 900843898?", true, "RAPPI", "XML:FE-5003"),
                new EvaluationQuestion("20. ¿En qué factura compré Café Molido Gourmet?", true, "Café Molido Gourmet", "XML:FE-5001"),
                new EvaluationQuestion("21. ¿Dónde compré Queso Maduro Holandés?", true, "Queso Maduro", "XML:FE-5002"),
                new EvaluationQuestion("22. ¿En qué documento aparece Rollos de Sushi Salmón?", true, "Rollos de Sushi", "XML:FE-5004"),
                new EvaluationQuestion("23. ¿En qué factura compré Jarabe Antialérgico Loratadina?", true, "Loratadina", "XML:FE-5007"),
                new EvaluationQuestion("24. ¿Qué dice el extracto bancario sobre la cuota de manejo en Bancolombia?", true, "Cuota de manejo", "PDF:extracto_bancolombia_octubre.pdf | PÁG:2"),
                new EvaluationQuestion("25. ¿Cuál fue el saldo inicial en el extracto de Bancolombia?", true, "Saldo inicial", "PDF:extracto_bancolombia_octubre.pdf | PÁG:1"),
                new EvaluationQuestion("26. ¿Cuánto gasté en el bloque de necesidades en octubre?", true, "1,935,750", "MANUAL:"),
                new EvaluationQuestion("27. ¿Cuánto gasté en el bloque de deseos en octubre?", true, "118,800", "XML:FE-5003"),
                new EvaluationQuestion("28. ¿Cuál es mi presupuesto inicial y distribución 50/30/20?", true, "4,000,000", "XML:"),
                new EvaluationQuestion("29. ¿Cuánto gasté en tiquetes de avión a Tokio?", false, "No tengo evidencia", ""),
                new EvaluationQuestion("30. ¿Cuánto invertí en Bitcoin y criptomonedas?", false, "No tengo evidencia", "")
        );

        int correctCount = RlsDataSourceConfig.runAsUser(userId, () -> {
            int passed = 0;
            for (EvaluationQuestion eq : thirtyQuestions) {
                NaturalLanguageChatService.ChatAnswerResponse resp = chatService.askQuestion(userId, eq.question());
                if (eq.shouldHaveEvidence()) {
                    String normalizedAnswer = resp.answer().replace(".", ",");
                    String normalizedExpected = eq.expectedSubstringInAnswer().replace(".", ",");
                    boolean textMatches = normalizedAnswer.toLowerCase().contains(normalizedExpected.toLowerCase());
                    boolean citationMatches = resp.citations().stream()
                            .anyMatch(c -> c.contains(eq.expectedCitationPrefix()));
                    if (resp.hasEvidence() && textMatches && citationMatches) {
                        passed++;
                    }
                } else {
                    if (!resp.hasEvidence() && resp.answer().contains("No tengo evidencia") && resp.citations().isEmpty()) {
                        passed++;
                    }
                }
            }
            return passed;
        });

        double accuracyPct = (correctCount * 100.0) / thirtyQuestions.size();
        assertThat(thirtyQuestions).hasSize(30);
        assertThat(accuracyPct)
                .as("Calidad del RAG en las 30 preguntas de evaluación (meta >= 80%%, RNF-09)")
                .isGreaterThanOrEqualTo(80.0);
    }

    private void ingestAndConfirmXml(
            UUID userId,
            String invoiceNumber,
            String cufe,
            String date,
            String merchant,
            String nit,
            String subtotal,
            String tax,
            String total,
            String itemDescription,
            String category,
            BudgetBlock block
    ) {
        String xml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                         xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                    <cbc:ID>%s</cbc:ID>
                    <cbc:UUID>%s</cbc:UUID>
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
                """, invoiceNumber, cufe, date, merchant, nit, tax, subtotal, total, subtotal, itemDescription);

        var uploaded = ingestionService.uploadAndProcessDocument(
                userId,
                invoiceNumber + ".xml",
                xml.getBytes(StandardCharsets.UTF_8),
                false,
                false
        );

        transactionService.confirmExtractedDocument(
                userId,
                uploaded.document().getId(),
                new TransactionService.ConfirmDocumentRequest(
                        merchant, nit, invoiceNumber, cufe,
                        LocalDate.parse(date),
                        new BigDecimal(subtotal),
                        new BigDecimal(tax),
                        new BigDecimal(total),
                        category,
                        block,
                        itemDescription,
                        itemDescription
                )
        );
    }

    private byte[] buildTwoPageBankStatementPdf() throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page1 = new PDPage();
            doc.addPage(page1);
            try (PDPageContentStream cs1 = new PDPageContentStream(doc, page1)) {
                cs1.beginText();
                cs1.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs1.newLineAtOffset(50, 700);
                cs1.showText("Extracto Bancario Bancolombia Octubre 2026 - Saldo inicial: 1500000 COP");
                cs1.endText();
            }

            PDPage page2 = new PDPage();
            doc.addPage(page2);
            try (PDPageContentStream cs2 = new PDPageContentStream(doc, page2)) {
                cs2.beginText();
                cs2.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs2.newLineAtOffset(50, 700);
                cs2.showText("Detalle de cobros bancarios Bancolombia - Cuota de manejo tarjeta debito: 14900 COP");
                cs2.endText();
            }

            doc.save(out);
            return out.toByteArray();
        }
    }
}
