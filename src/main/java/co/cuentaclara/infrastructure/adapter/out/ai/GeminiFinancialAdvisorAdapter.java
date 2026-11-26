package co.cuentaclara.infrastructure.adapter.out.ai;

import co.cuentaclara.domain.model.MonthAnalysisSnapshot;
import co.cuentaclara.domain.model.NarrativeOutput;
import co.cuentaclara.domain.model.NextMonthAllocation;
import co.cuentaclara.domain.port.out.FinancialAdvisorAiPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeminiFinancialAdvisorAdapter implements FinancialAdvisorAiPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiFinancialAdvisorAdapter.class);
    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String geminiApiKey;
    private final String geminiModel;

    public GeminiFinancialAdvisorAdapter(
            ObjectMapper objectMapper,
            @Value("${cuentaclara.ai.gemini-api-key:}") String geminiApiKey,
            @Value("${cuentaclara.ai.gemini-model:gemini-2.5-flash}") String geminiModel
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.geminiApiKey = geminiApiKey != null ? geminiApiKey.trim() : "";
        this.geminiModel = (geminiModel != null && !geminiModel.isBlank()) ? geminiModel.trim() : "gemini-2.5-flash";
    }

    @Override
    public boolean isGeminiConfigured() {
        return !geminiApiKey.isBlank();
    }

    @Override
    public String getActiveModelName() {
        return geminiModel;
    }

    @Override
    public NarrativeOutput generateMonthSummary(MonthAnalysisSnapshot snapshot) {
        String deterministicFallback = buildDeterministicMonthSummary(snapshot);
        if (!isGeminiConfigured()) {
            return new NarrativeOutput(deterministicFallback, false, "Motor determinista");
        }

        String systemPrompt = """
                Eres un asesor financiero personal formal, claro y preciso de CuentaClara (Colombia, moneda COP).
                REGLAS ESTRICTAS:
                1. NUNCA calcules, redondees ni inventes cifras. Usa EXCLUSIVAMENTE los montos y comercios incluidos en el JSON recibido.
                2. Redacta un resumen bien organizado del mes analizado en español formal y cercano, usando exactamente 3 secciones con subtítulos claros:
                   - **1. Compromisos obligatorios y necesidades**
                   - **2. Deseos y gastos hormiga**
                   - **3. Balance y ahorro alcanzado**
                3. Cita comercios o conceptos reales del JSON cuando estén disponibles. Si no hay gastos en un bloque, indícalo con claridad.
                4. Sé conciso (máximo 190 palabras), sin relleno ni emojis.
                """;

        try {
            String payloadJson = objectMapper.writeValueAsString(snapshot);
            String userPrompt = "Genera el resumen organizado de gastos para este mes:\n" + payloadJson;
            String responseText = callGemini(systemPrompt, userPrompt, 0.25, false);
            if (responseText != null && !responseText.isBlank()) {
                return new NarrativeOutput(responseText.trim(), true, geminiModel);
            }
        } catch (Exception ex) {
            log.warn("No se pudo generar resumen con Gemini ({}), usando fallback determinista: {}", geminiModel, ex.getMessage());
        }

        return new NarrativeOutput(deterministicFallback, false, "Motor determinista");
    }

    @Override
    public NarrativeOutput generateNextMonthRecommendation(
            MonthAnalysisSnapshot snapshot,
            NextMonthAllocation allocation
    ) {
        String deterministicFallback = buildDeterministicNextMonthRecommendation(snapshot, allocation);
        if (!isGeminiConfigured()) {
            return new NarrativeOutput(deterministicFallback, false, "Motor determinista");
        }

        String systemPrompt = """
                Eres un asesor financiero personal formal y práctico de CuentaClara (Colombia, moneda COP).
                REGLAS ESTRICTAS:
                1. NUNCA inventes cifras ni recalcules montos. Usa EXCLUSIVAMENTE los valores del JSON.
                2. Redacta la recomendación para el próximo periodo en español formal, estructurada exactamente en 3 apartados:
                   - **1. Dinero indispensable (no gastar en otros rubros):** explica cuánto debe reservarse obligatoriamente para deudas y necesidades básicas.
                   - **2. Meta recomendada (lo que deberías ahorrar):** explica la meta de ahorro del próximo mes y cómo protegerla desde el inicio del periodo.
                   - **3. Margen flexible y ahorro adicional (lo que podrías ahorrar):** indica cuánto queda para deseos y cuánto ahorro extra se lograría moderando los gastos hormiga o deseos observados (cita comercios del JSON si existen).
                3. Si los gastos esenciales superaron el objetivo, nunca recomiendes incumplir pagos básicos; sugiere ajustes realistas en deseos.
                4. Máximo 220 palabras, sin emojis ni promesas irreales.
                """;

        try {
            Map<String, Object> combined = Map.of(
                    "mesAnalizado", snapshot,
                    "planSiguienteMes", allocation
            );
            String payloadJson = objectMapper.writeValueAsString(combined);
            String userPrompt = "Genera la recomendación formal de manejo del dinero para el próximo mes:\n" + payloadJson;
            String responseText = callGemini(systemPrompt, userPrompt, 0.25, false);
            if (responseText != null && !responseText.isBlank()) {
                return new NarrativeOutput(responseText.trim(), true, geminiModel);
            }
        } catch (Exception ex) {
            log.warn("No se pudo generar recomendación con Gemini ({}), usando fallback determinista: {}", geminiModel, ex.getMessage());
        }

        return new NarrativeOutput(deterministicFallback, false, "Motor determinista");
    }

    @Override
    public String generateGroundedResponse(String systemInstruction, String userText) throws Exception {
        return callGemini(systemInstruction, userText, 0.15, false);
    }

    @Override
    public String generateJsonCompletion(String systemInstruction, String userText) throws Exception {
        return callGemini(systemInstruction, userText, 0.05, true);
    }

    protected String callGemini(
            String systemInstruction,
            String userText,
            double temperature,
            boolean jsonMode
    ) throws Exception {
        String url = GEMINI_BASE_URL + geminiModel + ":generateContent?key=" + geminiApiKey;

        Map<String, Object> generationConfig = new java.util.LinkedHashMap<>();
        generationConfig.put("temperature", temperature);
        generationConfig.put("maxOutputTokens", 900);
        if (jsonMode) {
            generationConfig.put("responseMimeType", "application/json");
        }

        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of(
                        "parts", List.of(Map.of("text", systemInstruction))
                ),
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(Map.of("text", userText))
                        )
                ),
                "generationConfig", generationConfig
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " desde Gemini: " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return null;
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.hasNonNull("text")) {
                sb.append(part.get("text").asText());
            }
        }
        return sb.toString();
    }

    private String buildDeterministicMonthSummary(MonthAnalysisSnapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("**1. Compromisos obligatorios y necesidades**\n");
        sb.append(String.format(
                "Durante %s registraste %s en gastos esenciales y obligaciones (frente a un tope objetivo del %d%% equivalente a %s). ",
                s.periodLabel(), formatCop(s.needsSpent()), s.needsPct(), formatCop(s.needsTarget())
        ));
        if (!s.topNeeds().isEmpty()) {
            sb.append("Los principales rubros fueron: ").append(String.join("; ", s.topNeeds())).append(".\n\n");
        } else {
            sb.append("No se registraron movimientos confirmados en este bloque.\n\n");
        }

        sb.append("**2. Deseos y gastos hormiga**\n");
        sb.append(String.format(
                "El gasto discrecional en deseos sumó %s (meta del %d%%: %s). ",
                formatCop(s.wantsSpent()), s.wantsPct(), formatCop(s.wantsTarget())
        ));
        if (s.smallExpensesCount() > 0) {
            sb.append(String.format(
                    "Dentro de este bloque se identificaron %d gastos hormiga (menores a $50.000 COP) que acumularon %s. ",
                    s.smallExpensesCount(), formatCop(s.smallExpensesTotal())
            ));
        }
        if (!s.topWants().isEmpty()) {
            sb.append("Destacan: ").append(String.join("; ", s.topWants())).append(".\n\n");
        } else {
            sb.append("No se registraron compras discrecionales en este periodo.\n\n");
        }

        sb.append("**3. Balance y ahorro alcanzado**\n");
        sb.append(String.format(
                "Sobre un ingreso base de %s, tu ahorro o remanente del periodo fue de %s (meta del %d%%: %s).",
                formatCop(s.actualIncome()), formatCop(s.savingsAchieved()), s.savingsPct(), formatCop(s.savingsTarget())
        ));
        return sb.toString();
    }

    private String buildDeterministicNextMonthRecommendation(MonthAnalysisSnapshot s, NextMonthAllocation a) {
        StringBuilder sb = new StringBuilder();
        sb.append("**1. Dinero indispensable (no gastar en otros rubros)**\n");
        sb.append(String.format(
                "Para %s debes reservar de manera prioritaria %s para cubrir deudas, pagos fijos (%s programados) y necesidades básicas. Este monto no debe destinarse a compras discrecionales.\n\n",
                a.nextPeriodLabel(), formatCop(a.indispensableAmount()), formatCop(s.fixedObligationsTotal())
        ));

        sb.append("**2. Meta recomendada (lo que deberías ahorrar)**\n");
        sb.append(String.format(
                "Según tu plan sugerido (%d%% / %d%% / %d%%), deberías apartar %s al inicio del mes como ahorro protegido.\n\n",
                a.needsPct(), a.wantsPct(), a.savingsPct(), formatCop(a.recommendedSavingsAmount())
        ));

        sb.append("**3. Margen flexible y ahorro adicional (lo que podrías ahorrar)**\n");
        sb.append(String.format(
                "Dispones de %s como tope para deseos y gustos personales. ",
                formatCop(a.flexibleWantsAmount())
        ));
        if (a.potentialExtraSavings().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format(
                    "Si reduces a la mitad los gastos hormiga observados (%s en %s), podrías sumar %s adicionales a tu ahorro mensual.",
                    formatCop(s.smallExpensesTotal()), s.periodLabel(), formatCop(a.potentialExtraSavings())
            ));
        } else {
            sb.append("Mantener estos rubros bajo el tope te permitirá conservar tu estabilidad sin afectar obligaciones.");
        }
        return sb.toString();
    }

    private String formatCop(BigDecimal val) {
        BigDecimal safe = val != null ? val : BigDecimal.ZERO;
        return String.format("$%,.0f COP", safe);
    }
}
