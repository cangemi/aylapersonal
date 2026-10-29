package ayla.whatsapp;

import ayla.chat.controller.ChatController;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestControllerAdvice(assignableTypes={WhatsappController.class,ChatController.class})
public class WhatsappExceptionHandler {
    @ExceptionHandler(WhatsappException.class)
    public ResponseEntity<Map<String,String>> handle(WhatsappException error) {
        return ResponseEntity.status(error.status()).body(Map.of("response",error.getMessage(),"provider","whatsapp"));
    }
}
