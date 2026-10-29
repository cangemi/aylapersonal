package ayla.hermes;

import ayla.chat.controller.ChatController;
import ayla.chat.dto.ChatErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = {ChatController.class, ayla.chat.controller.ConversationController.class, HermesController.class, VoiceController.class, ayla.memory.PersonalMemoryController.class, ayla.memory.PersonalMemoryBridge.class})
public class HermesExceptionHandler {
    @ExceptionHandler(ayla.finance.FinanceException.class)
    public ResponseEntity<ChatErrorResponse> finance(ayla.finance.FinanceException error) {
        return ResponseEntity.status(error.status()).body(new ChatErrorResponse(error.getMessage(), "finance"));
    }
    @ExceptionHandler(HermesException.class)
    public ResponseEntity<ChatErrorResponse> handle(HermesException error) {
        return ResponseEntity.status(error.status()).body(new ChatErrorResponse(error.getMessage(), "hermes"));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ChatErrorResponse> invalid(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(new ChatErrorResponse("Requisição inválida. Verifique os campos e seus tamanhos.", "hermes"));
    }
}
