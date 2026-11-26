package co.cuentaclara.infrastructure.adapter.out.persistence;

import co.cuentaclara.domain.model.BackedRecommendation;
import co.cuentaclara.domain.model.ParsedLineItem;
import co.cuentaclara.domain.model.TransactionQueryFilters;
import co.cuentaclara.domain.port.out.JsonSerializationPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JacksonJsonAdapter implements JsonSerializationPort {

    private final ObjectMapper objectMapper;

    public JacksonJsonAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String serializeRecommendations(List<BackedRecommendation> recommendations) {
        try {
            return objectMapper.writeValueAsString(recommendations);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    @Override
    public List<BackedRecommendation> deserializeRecommendations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    @Override
    public Optional<TransactionQueryFilters> parseChatIntentFilters(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Optional.empty();
        }
        try {
            String cleaned = rawJson.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
            }
            JsonNode node = objectMapper.readTree(cleaned);
            String yearMonth = textOrNull(node, "yearMonth");
            if (yearMonth != null && !yearMonth.matches("20\\d{2}-(0[1-9]|1[0-2])")) {
                yearMonth = null;
            }
            String category = textOrNull(node, "category");
            String block = textOrNull(node, "budgetBlock");
            if (block != null && !List.of("NEEDS", "WANTS", "SAVINGS").contains(block.toUpperCase())) {
                block = null;
            }
            String merchant = textOrNull(node, "merchantKeyword");
            return Optional.of(new TransactionQueryFilters(yearMonth, null, null, category, block, merchant));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<ParsedLineItem> deserializeItemsJson(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rawList = objectMapper.readValue(itemsJson, new TypeReference<>() {});
            List<ParsedLineItem> result = new ArrayList<>();
            for (Map<String, Object> item : rawList) {
                String desc = String.valueOf(item.getOrDefault("description", "Ítem"));
                BigDecimal qty = toBigDecimal(item.get("quantity"), BigDecimal.ONE);
                BigDecimal total = toBigDecimal(item.get("lineTotal"), BigDecimal.ZERO);
                result.add(new ParsedLineItem(desc, qty, total));
            }
            return result;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private BigDecimal toBigDecimal(Object val, BigDecimal fallback) {
        if (val == null) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(val));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode valueNode = node.get(field);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        String s = valueNode.asText();
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s.trim())) ? null : s.trim();
    }
}
