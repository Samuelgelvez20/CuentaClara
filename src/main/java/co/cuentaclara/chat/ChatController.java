package co.cuentaclara.chat;

import co.cuentaclara.security.jwt.AuthenticatedUserPrincipal;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final NaturalLanguageChatService chatService;

    public ChatController(NaturalLanguageChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<?> ask(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestBody ChatRequest request
    ) {
        try {
            NaturalLanguageChatService.ChatAnswerResponse response =
                    chatService.askQuestion(principal.userId(), request.question());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    public record ChatRequest(@NotBlank String question) {
    }
}
