package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.MonthAnalysisSnapshot;
import co.cuentaclara.domain.model.NarrativeOutput;
import co.cuentaclara.domain.model.NextMonthAllocation;

public interface FinancialAdvisorAiPort {

    boolean isGeminiConfigured();

    String getActiveModelName();

    NarrativeOutput generateMonthSummary(MonthAnalysisSnapshot snapshot);

    NarrativeOutput generateNextMonthRecommendation(MonthAnalysisSnapshot snapshot, NextMonthAllocation nextPlan);

    String generateGroundedResponse(String systemInstruction, String userText) throws Exception;

    String generateJsonCompletion(String systemInstruction, String userText) throws Exception;
}
