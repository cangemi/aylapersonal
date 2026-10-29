package ayla.calendar;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
@RestControllerAdvice(assignableTypes={CalendarController.class,CalendarBridge.class})
public class CalendarExceptionHandler {
    @ExceptionHandler(ayla.hermes.HermesException.class) public ResponseEntity<?> error(ayla.hermes.HermesException error){return ResponseEntity.status(error.status()).body(Map.of("error",error.getMessage()));}
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid(Exception ignored){return ResponseEntity.badRequest().body(Map.of("error","Verifique o compromisso, datas, fuso e lembretes."));}
}
