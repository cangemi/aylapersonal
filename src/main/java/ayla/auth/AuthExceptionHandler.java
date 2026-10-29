package ayla.auth;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice(assignableTypes=AuthController.class)
public class AuthExceptionHandler {
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<?> auth(AuthException e){return ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore()).body(Map.of("error",e.getMessage()));}
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid(Exception e){return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("error","Verifique os campos da solicitação."));}
}
