package co.cuentaclara.document.xml;

import co.cuentaclara.profile.BudgetBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Parser determinista y seguro (XXE deshabilitado) para Factura Electrónica DIAN Colombia UBL 2.1
 * (soporta <Invoice>, <CreditNote>, <DebitNote> y contenedor <AttachedDocument> con CDATA interno) (RF-04).
 */
@Component
public class DianUblXmlParser {

    private final ObjectMapper objectMapper;

    public DianUblXmlParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedUblInvoice parse(byte[] xmlBytes) {
        try {
            Document doc = parseSecureXmlDocument(new InputSource(new ByteArrayInputStream(xmlBytes)));
            String rootName = getLocalOrNodeName(doc.getDocumentElement());

            // Si es un AttachedDocument de la DIAN, extraer el XML interno de <cbc:Description>
            if ("AttachedDocument".equalsIgnoreCase(rootName)) {
                XPath xpath = XPathFactory.newInstance().newXPath();
                String embeddedXml = (String) xpath.evaluate(
                        "//*[local-name()='Attachment']//*[local-name()='Description']/text()",
                        doc,
                        XPathConstants.STRING
                );
                if (embeddedXml != null && embeddedXml.trim().startsWith("<")) {
                    doc = parseSecureXmlDocument(new InputSource(new StringReader(embeddedXml.trim())));
                }
            }

            XPath xpath = XPathFactory.newInstance().newXPath();

            String invoiceNumber = firstNonBlank(
                    evalString(xpath, "/*/*[local-name()='ID']/text()", doc),
                    evalString(xpath, "//*[local-name()='ParentDocumentID']/text()", doc),
                    "SIN-NUMERO"
            );

            String cufe = firstNonBlank(
                    evalString(xpath, "/*/*[local-name()='UUID']/text()", doc),
                    null
            );

            String issueDateStr = firstNonBlank(
                    evalString(xpath, "/*/*[local-name()='IssueDate']/text()", doc),
                    LocalDate.now().toString()
            );
            LocalDate issueDate = parseDateSafely(issueDateStr);

            String merchantName = firstNonBlank(
                    evalString(xpath, "//*[local-name()='AccountingSupplierParty']//*[local-name()='RegistrationName']/text()", doc),
                    evalString(xpath, "//*[local-name()='AccountingSupplierParty']//*[local-name()='PartyName']/*[local-name()='Name']/text()", doc),
                    evalString(xpath, "//*[local-name()='SenderParty']//*[local-name()='RegistrationName']/text()", doc),
                    "Comercio Desconocido"
            );

            String merchantNit = firstNonBlank(
                    evalString(xpath, "//*[local-name()='AccountingSupplierParty']//*[local-name()='PartyTaxScheme']/*[local-name()='CompanyID']/text()", doc),
                    evalString(xpath, "//*[local-name()='AccountingSupplierParty']//*[local-name()='CompanyID']/text()", doc),
                    evalString(xpath, "//*[local-name()='SenderParty']//*[local-name()='CompanyID']/text()", doc),
                    "0"
            );

            BigDecimal subtotal = parseAmount(firstNonBlank(
                    evalString(xpath, "//*[local-name()='LegalMonetaryTotal']/*[local-name()='LineExtensionAmount']/text()", doc),
                    evalString(xpath, "//*[local-name()='LegalMonetaryTotal']/*[local-name()='TaxExclusiveAmount']/text()", doc),
                    "0"
            ));

            BigDecimal taxAmount = parseAmount(firstNonBlank(
                    evalString(xpath, "/*/*[local-name()='TaxTotal'][1]/*[local-name()='TaxAmount']/text()", doc),
                    "0"
            ));

            BigDecimal discountAmount = parseAmount(firstNonBlank(
                    evalString(xpath, "//*[local-name()='LegalMonetaryTotal']/*[local-name()='AllowanceTotalAmount']/text()", doc),
                    "0"
            ));

            BigDecimal totalAmount = parseAmount(firstNonBlank(
                    evalString(xpath, "//*[local-name()='LegalMonetaryTotal']/*[local-name()='PayableAmount']/text()", doc),
                    evalString(xpath, "//*[local-name()='LegalMonetaryTotal']/*[local-name()='TaxInclusiveAmount']/text()", doc),
                    subtotal.add(taxAmount).subtract(discountAmount).toPlainString()
            ));

            List<ParsedLineItem> items = extractLineItems(xpath, doc);
            String itemsJson = serializeItems(items);
            CategoryHeuristic suggestion = suggestInitialCategory(merchantName, items);

            StringBuilder rawSummary = new StringBuilder();
            rawSummary.append("Factura Electrónica UBL 2.1 No. ").append(invoiceNumber)
                    .append(" | Emisor: ").append(merchantName)
                    .append(" (NIT ").append(merchantNit).append(")")
                    .append(" | Fecha: ").append(issueDate)
                    .append(" | Subtotal: ").append(subtotal)
                    .append(" | Impuestos: ").append(taxAmount)
                    .append(" | Total: ").append(totalAmount);
            if (cufe != null && !cufe.isBlank()) {
                rawSummary.append(" | CUFE: ").append(cufe);
            }
            for (ParsedLineItem item : items) {
                rawSummary.append("\n - Ítem: ").append(item.description())
                        .append(" (Cant: ").append(item.quantity())
                        .append(", Valor: ").append(item.lineTotal()).append(")");
            }

            return new ParsedUblInvoice(
                    merchantName.trim(),
                    merchantNit.trim(),
                    invoiceNumber.trim(),
                    cufe != null && !cufe.isBlank() ? cufe.trim() : null,
                    issueDate,
                    subtotal,
                    taxAmount,
                    discountAmount,
                    totalAmount,
                    items,
                    itemsJson,
                    suggestion.category(),
                    suggestion.budgetBlock(),
                    rawSummary.toString()
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("No se pudo interpretar el archivo XML UBL 2.1: " + ex.getMessage(), ex);
        }
    }

    private Document parseSecureXmlDocument(InputSource inputSource) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Deshabilitar entidades externas (prevención XXE)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(inputSource);
    }

    private List<ParsedLineItem> extractLineItems(XPath xpath, Document doc) throws Exception {
        List<ParsedLineItem> items = new ArrayList<>();
        NodeList lineNodes = (NodeList) xpath.evaluate(
                "//*[local-name()='InvoiceLine' or local-name()='CreditNoteLine' or local-name()='DebitNoteLine']",
                doc,
                XPathConstants.NODESET
        );
        for (int i = 0; i < lineNodes.getLength(); i++) {
            Node line = lineNodes.item(i);
            String description = firstNonBlank(
                    evalString(xpath, ".//*[local-name()='Item']/*[local-name()='Description']/text()", line),
                    evalString(xpath, ".//*[local-name()='Note']/text()", line),
                    "Ítem " + (i + 1)
            );
            BigDecimal qty = parseAmount(firstNonBlank(
                    evalString(xpath, ".//*[local-name()='InvoicedQuantity' or local-name()='CreditedQuantity' or local-name()='DebitedQuantity']/text()", line),
                    "1"
            ));
            BigDecimal lineTotal = parseAmount(firstNonBlank(
                    evalString(xpath, ".//*[local-name()='LineExtensionAmount']/text()", line),
                    "0"
            ));
            items.add(new ParsedLineItem(description.trim(), qty, lineTotal));
        }
        return items;
    }

    private CategoryHeuristic suggestInitialCategory(String merchantName, List<ParsedLineItem> items) {
        String combined = (merchantName + " " + items.stream().map(ParsedLineItem::description).reduce("", (a, b) -> a + " " + b))
                .toLowerCase(Locale.ROOT);

        if (combined.contains("rappi") || combined.contains("uber eats") || combined.contains("didi food")
                || combined.contains("domicilio") || combined.contains("restaurante") || combined.contains("hamburguesa")) {
            return new CategoryHeuristic("Domicilios y Restaurantes", BudgetBlock.WANTS);
        }
        if (combined.contains("éxito") || combined.contains("exito") || combined.contains("carulla")
                || combined.contains("d1") || combined.contains("ara") || combined.contains("olimpica")
                || combined.contains("mercado") || combined.contains("supermercado")) {
            return new CategoryHeuristic("Alimentación", BudgetBlock.NEEDS);
        }
        if (combined.contains("epm") || combined.contains("enel") || combined.contains("vanti")
                || combined.contains("acueducto") || combined.contains("claro") || combined.contains("movistar")
                || combined.contains("tigo") || combined.contains("servicio")) {
            return new CategoryHeuristic("Servicios", BudgetBlock.NEEDS);
        }
        if (combined.contains("farmatodo") || combined.contains("cruz verde") || combined.contains("drogueria")
                || combined.contains("salud") || combined.contains("medicina")) {
            return new CategoryHeuristic("Salud", BudgetBlock.NEEDS);
        }
        if (combined.contains("uber") || combined.contains("cabify") || combined.contains("terpel")
                || combined.contains("primax") || combined.contains("transporte") || combined.contains("gasolina")) {
            return new CategoryHeuristic("Transporte", BudgetBlock.NEEDS);
        }
        return new CategoryHeuristic("Otros", BudgetBlock.WANTS);
    }

    private String serializeItems(List<ParsedLineItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private String evalString(XPath xpath, String expr, Object item) throws Exception {
        String val = (String) xpath.evaluate(expr, item, XPathConstants.STRING);
        return val != null ? val.trim() : "";
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private LocalDate parseDateSafely(String raw) {
        try {
            String clean = raw.trim();
            if (clean.length() >= 10) {
                return LocalDate.parse(clean.substring(0, 10));
            }
            return LocalDate.now();
        } catch (Exception ex) {
            return LocalDate.now();
        }
    }

    private String getLocalOrNodeName(Node node) {
        return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
    }

    private record CategoryHeuristic(String category, BudgetBlock budgetBlock) {
    }

    public record ParsedLineItem(
            String description,
            BigDecimal quantity,
            BigDecimal lineTotal
    ) {
    }

    public record ParsedUblInvoice(
            String merchantName,
            String merchantNit,
            String invoiceNumber,
            String cufe,
            LocalDate issueDate,
            BigDecimal subtotalAmount,
            BigDecimal taxAmount,
            BigDecimal discountAmount,
            BigDecimal totalAmount,
            List<ParsedLineItem> items,
            String itemsJson,
            String suggestedCategory,
            BudgetBlock suggestedBudgetBlock,
            String rawSummaryText
    ) {
        public boolean issuerValidForDuplicateCheck() {
            return merchantNit != null && !merchantNit.isBlank() && !"0".equals(merchantNit)
                    && invoiceNumber != null && !invoiceNumber.isBlank() && !"SIN-NUMERO".equals(invoiceNumber);
        }
    }
}
