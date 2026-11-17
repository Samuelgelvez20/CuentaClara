package co.cuentaclara.document.ocr;

import co.cuentaclara.document.xml.DianUblXmlParser;
import co.cuentaclara.profile.BudgetBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Estructurador de texto crudo (proveniente de PDFBox o Tesseract OCR) hacia campos financieros
 * estructurados (comercio, NIT, número de factura, fecha, ítems, subtotal, IVA y total) (RF-04).
 */
@Component
public class StructuredInvoiceTextExtractor {

    private static final Pattern MERCHANT_LINE_PATTERN = Pattern.compile(
            "(?im)^(?:COMERCIO|EMISOR|RAZ[OÓ]N\\s+SOCIAL|TIENDA|RESTAURANTE)\\s*[:\\-]\\s*(.+)$"
    );
    private static final Pattern NIT_PATTERN = Pattern.compile(
            "(?i)\\bNIT\\s*[:#\\-]?\\s*([0-9.\\-]{6,20})"
    );
    private static final Pattern INVOICE_NUM_PATTERN = Pattern.compile(
            "(?i)(?:FACTURA(?:\\s+ELECTR[OÓ]NICA)?(?:\\s+DE\\s+VENTA)?|RECIBO|TIRILLA|NO\\.|NRO\\.|#)\\s*[:#\\-]?\\s*([A-Z0-9\\-]{3,30})"
    );
    private static final Pattern CUFE_PATTERN = Pattern.compile(
            "(?i)\\bCUFE\\s*[:\\-]?\\s*([a-f0-9]{20,128})"
    );
    private static final Pattern DATE_ISO_PATTERN = Pattern.compile(
            "\\b(20\\d{2}[-/](?:0[1-9]|1[0-2])[-/](?:0[1-9]|[12]\\d|3[01]))\\b"
    );
    private static final Pattern DATE_DMY_PATTERN = Pattern.compile(
            "\\b((?:0[1-9]|[12]\\d|3[01])[/-](?:0[1-9]|1[0-2])[/-]20\\d{2})\\b"
    );
    private static final Pattern SUBTOTAL_PATTERN = Pattern.compile(
            "(?im)^.*\\b(?:SUBTOTAL|BASE\\s+GRAVABLE|VALOR\\s+BRUTO)\\b[^0-9$]*\\$?\\s*([0-9.,]+)"
    );
    private static final Pattern TAX_PATTERN = Pattern.compile(
            "(?im)^.*\\b(?:IVA|IMPUESTO(?:S)?|IMPOCONSUMO)\\b[^0-9$]*\\$?\\s*([0-9.,]+)"
    );
    private static final Pattern TOTAL_PATTERN = Pattern.compile(
            "(?im)^.*\\b(?:TOTAL\\s+A\\s+PAGAR|VALOR\\s+TOTAL|TOTAL\\s+FACTURA|TOTAL)\\b[^0-9$]*\\$?\\s*([0-9.,]+)"
    );
    private static final Pattern ITEM_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:[-*•]|\\d+[.)]?)?\\s*([A-Za-zÁÉÍÓÚáéíóúÑñ0-9][A-Za-zÁÉÍÓÚáéíóúÑñ0-9\\s/().,-]{2,60}?)\\s+(?:x?(\\d+(?:[.,]\\d+)?)\\s+)?\\$?\\s*([0-9]{1,3}(?:[.,][0-9]{3})+(?:[.,][0-9]{2})?|[0-9]{4,10}(?:[.,][0-9]{2})?)\\s*$"
    );

    private final ObjectMapper objectMapper;

    public StructuredInvoiceTextExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DianUblXmlParser.ParsedUblInvoice extractFromRawText(String rawText) {
        String safeText = rawText != null ? rawText.trim() : "";

        String merchantName = extractMerchantName(safeText);
        String merchantNit = findFirstGroup(NIT_PATTERN, safeText, "0").replaceAll("[^0-9\\-]", "");
        String invoiceNumber = findFirstGroup(INVOICE_NUM_PATTERN, safeText, "SIN-NUMERO");
        String cufe = findFirstGroup(CUFE_PATTERN, safeText, null);
        LocalDate issueDate = extractDate(safeText);

        BigDecimal subtotal = parseColombianAmount(findFirstGroup(SUBTOTAL_PATTERN, safeText, null));
        BigDecimal tax = parseColombianAmount(findFirstGroup(TAX_PATTERN, safeText, "0"));
        BigDecimal total = parseColombianAmount(findFirstGroup(TOTAL_PATTERN, safeText, null));

        List<DianUblXmlParser.ParsedLineItem> items = extractItems(safeText);
        BigDecimal itemsSum = items.stream()
                .map(DianUblXmlParser.ParsedLineItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (total.compareTo(BigDecimal.ZERO) == 0 && itemsSum.compareTo(BigDecimal.ZERO) > 0) {
            total = itemsSum.add(tax);
        }
        if (subtotal.compareTo(BigDecimal.ZERO) == 0 && total.compareTo(BigDecimal.ZERO) > 0) {
            subtotal = total.subtract(tax).max(BigDecimal.ZERO);
        }

        String itemsJson;
        try {
            itemsJson = objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException ex) {
            itemsJson = "[]";
        }

        CategorySuggestion suggestion = classifyCategory(merchantName, safeText);

        return new DianUblXmlParser.ParsedUblInvoice(
                merchantName,
                merchantNit.isBlank() ? "0" : merchantNit,
                invoiceNumber,
                cufe,
                issueDate,
                subtotal,
                tax,
                BigDecimal.ZERO,
                total,
                items,
                itemsJson,
                suggestion.category(),
                suggestion.block(),
                safeText
        );
    }

    private String extractMerchantName(String text) {
        String explicit = findFirstGroup(MERCHANT_LINE_PATTERN, text, null);
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        String[] lines = text.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() >= 3
                    && !trimmed.toUpperCase(Locale.ROOT).startsWith("FACTURA")
                    && !trimmed.toUpperCase(Locale.ROOT).startsWith("NIT")
                    && !trimmed.toUpperCase(Locale.ROOT).startsWith("FECHA")) {
                return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
            }
        }
        return "Comercio por confirmar";
    }

    private LocalDate extractDate(String text) {
        String iso = findFirstGroup(DATE_ISO_PATTERN, text, null);
        if (iso != null) {
            try {
                return LocalDate.parse(iso.replace('/', '-'));
            } catch (Exception ignored) {
            }
        }
        String dmy = findFirstGroup(DATE_DMY_PATTERN, text, null);
        if (dmy != null) {
            try {
                return LocalDate.parse(dmy.replace('/', '-'), DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            } catch (Exception ignored) {
            }
        }
        return LocalDate.now();
    }

    private List<DianUblXmlParser.ParsedLineItem> extractItems(String text) {
        List<DianUblXmlParser.ParsedLineItem> list = new ArrayList<>();
        Matcher matcher = ITEM_LINE_PATTERN.matcher(text);
        while (matcher.find()) {
            String desc = matcher.group(1).trim();
            String upper = desc.toUpperCase(Locale.ROOT);
            if (upper.contains("SUBTOTAL") || upper.contains("TOTAL") || upper.contains("IVA")
                    || upper.contains("IMPUESTO") || upper.contains("NIT") || upper.contains("CAMBIO")) {
                continue;
            }
            BigDecimal qty = matcher.group(2) != null ? parseColombianAmount(matcher.group(2)) : BigDecimal.ONE;
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                qty = BigDecimal.ONE;
            }
            BigDecimal amount = parseColombianAmount(matcher.group(3));
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                list.add(new DianUblXmlParser.ParsedLineItem(desc, qty, amount));
            }
        }
        return list;
    }

    public static BigDecimal parseColombianAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        String cleaned = raw.replace("$", "").replace(" ", "").trim();
        // Si tiene formato 150.000,00 (punto de miles y coma decimal)
        if (cleaned.contains(".") && cleaned.contains(",")) {
            if (cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.')) {
                cleaned = cleaned.replace(".", "").replace(",", ".");
            } else {
                cleaned = cleaned.replace(",", "");
            }
        } else if (cleaned.contains(".")) {
            // Si termina en .000 (separador de miles colombiano sin decimales)
            int lastDot = cleaned.lastIndexOf('.');
            if (cleaned.length() - lastDot - 1 == 3 && cleaned.indexOf('.') == lastDot) {
                cleaned = cleaned.replace(".", "");
            } else if (cleaned.indexOf('.') != lastDot) {
                cleaned = cleaned.replace(".", "");
            }
        } else if (cleaned.contains(",")) {
            int lastComma = cleaned.lastIndexOf(',');
            if (cleaned.length() - lastComma - 1 == 2) {
                cleaned = cleaned.replace(",", ".");
            } else {
                cleaned = cleaned.replace(",", "");
            }
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private CategorySuggestion classifyCategory(String merchant, String rawText) {
        String lower = (merchant + " " + rawText).toLowerCase(Locale.ROOT);
        if (lower.contains("rappi") || lower.contains("uber eats") || lower.contains("didi")
                || lower.contains("domicilio") || lower.contains("restaurante") || lower.contains("corral")
                || lower.contains("crepes") || lower.contains("juan valdez") || lower.contains("pizza")) {
            return new CategorySuggestion("Domicilios y Restaurantes", BudgetBlock.WANTS);
        }
        if (lower.contains("éxito") || lower.contains("exito") || lower.contains("carulla")
                || lower.contains("d1") || lower.contains("ara") || lower.contains("olimpica")
                || lower.contains("alkosto") || lower.contains("mercado") || lower.contains("supermercado")) {
            return new CategorySuggestion("Alimentación", BudgetBlock.NEEDS);
        }
        if (lower.contains("epm") || lower.contains("enel") || lower.contains("vanti")
                || lower.contains("acueducto") || lower.contains("claro") || lower.contains("movistar")
                || lower.contains("tigo") || lower.contains("etb") || lower.contains("energía")) {
            return new CategorySuggestion("Servicios", BudgetBlock.NEEDS);
        }
        if (lower.contains("cruz verde") || lower.contains("farmatodo") || lower.contains("pasteur")
                || lower.contains("colsubsidio") || lower.contains("droguería") || lower.contains("medicina")) {
            return new CategorySuggestion("Salud", BudgetBlock.NEEDS);
        }
        if (lower.contains("terpel") || lower.contains("primax") || lower.contains("biomax")
                || lower.contains("uber") || lower.contains("cabify") || lower.contains("transporte")) {
            return new CategorySuggestion("Transporte", BudgetBlock.NEEDS);
        }
        return new CategorySuggestion("Otros", BudgetBlock.WANTS);
    }

    private String findFirstGroup(Pattern pattern, String text, String fallback) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return fallback;
    }

    private record CategorySuggestion(String category, BudgetBlock block) {
    }
}
