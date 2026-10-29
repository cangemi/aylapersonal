package ayla.finance;

import ayla.hermes.HermesException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice(assignableTypes={FinanceController.class,FinanceBridgeController.class,
        ayla.investment.InvestmentController.class,ayla.investment.InvestmentBridge.class})
public class FinanceExceptionHandler {
    @ExceptionHandler(FinanceException.class)
    public ResponseEntity<?> finance(FinanceException e) { return ResponseEntity.status(e.status()).body(Map.of("error",e.getMessage())); }
    @ExceptionHandler(HermesException.class)
    public ResponseEntity<?> identity(HermesException e) { return ResponseEntity.status(e.status()).body(Map.of("error",e.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error","Requisição inválida. Verifique os campos, valores e datas.")); }
}
