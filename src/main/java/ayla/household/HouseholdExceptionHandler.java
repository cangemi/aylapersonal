package ayla.household;

import java.util.Map;
import ayla.hermes.HermesException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice(assignableTypes={HouseholdController.class,HouseholdBridge.class})
public class HouseholdExceptionHandler {
    @ExceptionHandler(HouseholdException.class)
    public ResponseEntity<?> household(HouseholdException e) {return ResponseEntity.status(e.status()).body(Map.of("error",e.getMessage()));}
    @ExceptionHandler(HermesException.class)
    public ResponseEntity<?> identity(HermesException e) {return ResponseEntity.status(e.status()).body(Map.of("error",e.getMessage()));}
    @ExceptionHandler({MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid(Exception e) {return ResponseEntity.badRequest().body(Map.of("error","Verifique nome, quantidade, unidade, datas e versão do item."));}
}
