package co.cuentaclara.infrastructure.config;

import co.cuentaclara.application.service.AuthApplicationService;
import co.cuentaclara.application.service.ChatAssistantApplicationService;
import co.cuentaclara.application.service.DocumentIngestionApplicationService;
import co.cuentaclara.application.service.FinancialProfileApplicationService;
import co.cuentaclara.application.service.MonthlyReportApplicationService;
import co.cuentaclara.application.service.RagIndexingApplicationService;
import co.cuentaclara.application.service.TransactionApplicationService;
import co.cuentaclara.domain.port.in.AuthUseCase;
import co.cuentaclara.domain.port.in.ChatAssistantUseCase;
import co.cuentaclara.domain.port.in.DocumentIngestionUseCase;
import co.cuentaclara.domain.port.in.FinancialProfileUseCase;
import co.cuentaclara.domain.port.in.MonthlyReportUseCase;
import co.cuentaclara.domain.port.out.AccessTokenPort;
import co.cuentaclara.domain.port.out.AiCacheAndCostGuardPort;
import co.cuentaclara.domain.port.out.BudgetPlanPersistencePort;
import co.cuentaclara.domain.port.out.BudgetRepositoryPort;
import co.cuentaclara.domain.port.out.DocumentExtractionPort;
import co.cuentaclara.domain.port.out.DocumentExtractionRepositoryPort;
import co.cuentaclara.domain.port.out.DocumentJobQueuePort;
import co.cuentaclara.domain.port.out.DocumentRepositoryPort;
import co.cuentaclara.domain.port.out.EmbeddingPort;
import co.cuentaclara.domain.port.out.FileSecurityValidatorPort;
import co.cuentaclara.domain.port.out.FinancialAdvisorAiPort;
import co.cuentaclara.domain.port.out.FinancialProfileRepositoryPort;
import co.cuentaclara.domain.port.out.FirebaseTokenVerifierPort;
import co.cuentaclara.domain.port.out.GoogleTokenVerifierPort;
import co.cuentaclara.domain.port.out.HybridVectorSearchPort;
import co.cuentaclara.domain.port.out.JsonSerializationPort;
import co.cuentaclara.domain.port.out.MonthlyReportRepositoryPort;
import co.cuentaclara.domain.port.out.PasswordHasherPort;
import co.cuentaclara.domain.port.out.RecurringObligationRepositoryPort;
import co.cuentaclara.domain.port.out.TempFileStoragePort;
import co.cuentaclara.domain.port.out.TenantExecutionPort;
import co.cuentaclara.domain.port.out.TransactionAnalyticsQueryPort;
import co.cuentaclara.domain.port.out.TransactionRepositoryPort;
import co.cuentaclara.domain.port.out.TransactionRunnerPort;
import co.cuentaclara.domain.port.out.UserRepositoryPort;
import co.cuentaclara.domain.port.out.VectorIndexPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ensambla los casos de uso puros de la capa Application con los puertos y adaptadores de Infrastructure.
 */
@Configuration
public class HexagonalWiringConfig {

    @Bean
    public AuthApplicationService authApplicationService(
            UserRepositoryPort userRepository,
            PasswordHasherPort passwordHasher,
            AccessTokenPort accessTokenPort,
            GoogleTokenVerifierPort googleTokenVerifier,
            FirebaseTokenVerifierPort firebaseTokenVerifier,
            TempFileStoragePort tempFileStorage,
            TransactionRunnerPort transactionRunner
    ) {
        return new AuthApplicationService(
                userRepository,
                passwordHasher,
                accessTokenPort,
                googleTokenVerifier,
                firebaseTokenVerifier,
                tempFileStorage,
                transactionRunner
        );
    }

    @Bean
    public FinancialProfileApplicationService financialProfileApplicationService(
            FinancialProfileRepositoryPort profileRepository,
            RecurringObligationRepositoryPort obligationRepository,
            BudgetRepositoryPort budgetRepository,
            TransactionRunnerPort transactionRunner
    ) {
        return new FinancialProfileApplicationService(
                profileRepository,
                obligationRepository,
                budgetRepository,
                transactionRunner
        );
    }

    @Bean
    public DocumentIngestionApplicationService documentIngestionApplicationService(
            FileSecurityValidatorPort fileValidator,
            DocumentRepositoryPort documentRepository,
            DocumentExtractionRepositoryPort extractionRepository,
            TempFileStoragePort tempFileStorage,
            DocumentExtractionPort documentExtractor,
            DocumentJobQueuePort jobQueuePort,
            TenantExecutionPort tenantExecutionPort,
            TransactionRunnerPort transactionRunner
    ) {
        return new DocumentIngestionApplicationService(
                fileValidator,
                documentRepository,
                extractionRepository,
                tempFileStorage,
                documentExtractor,
                jobQueuePort,
                tenantExecutionPort,
                transactionRunner
        );
    }

    @Bean
    public RagIndexingApplicationService ragIndexingApplicationService(
            VectorIndexPort vectorIndexPort,
            DocumentRepositoryPort documentRepository,
            TempFileStoragePort tempFileStorage,
            JsonSerializationPort jsonSerializationPort,
            TransactionRunnerPort transactionRunner
    ) {
        return new RagIndexingApplicationService(
                vectorIndexPort,
                documentRepository,
                tempFileStorage,
                jsonSerializationPort,
                transactionRunner
        );
    }

    @Bean
    public TransactionApplicationService transactionApplicationService(
            TransactionRepositoryPort transactionRepository,
            DocumentRepositoryPort documentRepository,
            DocumentExtractionRepositoryPort extractionRepository,
            DocumentIngestionUseCase documentIngestionUseCase,
            TransactionAnalyticsQueryPort analyticsQueryPort,
            RagIndexingApplicationService ragIndexingApplicationService,
            TransactionRunnerPort transactionRunner
    ) {
        return new TransactionApplicationService(
                transactionRepository,
                documentRepository,
                extractionRepository,
                documentIngestionUseCase,
                analyticsQueryPort,
                ragIndexingApplicationService,
                transactionRunner
        );
    }

    @Bean
    public MonthlyReportApplicationService monthlyReportApplicationService(
            FinancialProfileRepositoryPort profileRepository,
            RecurringObligationRepositoryPort obligationRepository,
            BudgetRepositoryPort budgetRepository,
            MonthlyReportRepositoryPort reportRepository,
            TransactionAnalyticsQueryPort analyticsQueryPort,
            BudgetPlanPersistencePort budgetPlanPersistencePort,
            FinancialAdvisorAiPort advisorAiPort,
            JsonSerializationPort jsonSerializationPort,
            TransactionApplicationService transactionApplicationService,
            TransactionRunnerPort transactionRunner
    ) {
        return new MonthlyReportApplicationService(
                profileRepository,
                obligationRepository,
                budgetRepository,
                reportRepository,
                analyticsQueryPort,
                budgetPlanPersistencePort,
                advisorAiPort,
                jsonSerializationPort,
                transactionApplicationService,
                transactionRunner
        );
    }

    @Bean
    public ChatAssistantApplicationService chatAssistantApplicationService(
            HybridVectorSearchPort hybridSearchPort,
            FinancialProfileUseCase profileUseCase,
            FinancialAdvisorAiPort advisorAiPort,
            JsonSerializationPort jsonSerializationPort,
            AiCacheAndCostGuardPort costGuardAndCache,
            EmbeddingPort embeddingProvider,
            TransactionRunnerPort transactionRunner,
            @Value("${cuentaclara.chat.gemini-enabled:true}") boolean geminiEnabled
    ) {
        return new ChatAssistantApplicationService(
                hybridSearchPort,
                profileUseCase,
                advisorAiPort,
                jsonSerializationPort,
                costGuardAndCache,
                embeddingProvider,
                transactionRunner,
                geminiEnabled
        );
    }
}
