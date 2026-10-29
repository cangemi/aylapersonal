package ayla.investment;

import static ayla.investment.InvestmentData.*;
import ayla.finance.FinanceException;
import io.swagger.v3.oas.annotations.Hidden;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@RequestMapping("/internal/hermes/investment")
public class InvestmentBridge {
    private final InvestmentService service;
    private final JdbcTemplate jdbc;
    private final byte[] key;
    public InvestmentBridge(InvestmentService service, JdbcTemplate jdbc, @Value("${ayla.finance.bridge-key:}") String key) {
        this.service = service; this.jdbc = jdbc; this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    /** Valores em reais como texto ("1500.50"), convertidos aqui para centavos exatos. */
    public record Command(String initialAmount, String monthlyAmount, Integer months, BigDecimal cdiAnnualPercent, BigDecimal percentCdi) {}

    @PostMapping("/{operation}")
    public Object execute(@RequestHeader(value = "Authorization", defaultValue = "") String authorization,
            @RequestHeader(value = "X-Ayla-Session", defaultValue = "") String session,
            @PathVariable String operation, @RequestBody Command command) {
        if (key.length < 32 || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(key, authorization.substring(7).getBytes(StandardCharsets.UTF_8)))
            throw new FinanceException(401, "Integração de investimentos não autorizada.");
        var owners = jdbc.queryForList("select user_id from chat_conversations where hermes_session_id=?", String.class, session);
        if (owners.size() != 1) throw new FinanceException(403, "Abra esta conversa pela API Java para acessar os investimentos.");
        String user = owners.getFirst();
        ayla.monitoring.MonitoringContext.user(user);
        return switch (operation) {
            case "overview" -> service.overview(user);
            case "simulate" -> service.simulate(user, new SimulationInput(cents(command.initialAmount()), cents(command.monthlyAmount()),
                    command.months() == null ? 12 : command.months(), command.cdiAnnualPercent(), command.percentCdi()));
            default -> throw new FinanceException(404, "Operação não encontrada.");
        };
    }

    private static Long cents(String reais) {
        if (reais == null || reais.isBlank()) return null;
        try {
            var value = new BigDecimal(reais.strip().replace(',', '.'));
            if (value.signum() < 0 || value.scale() > 2 && value.stripTrailingZeros().scale() > 2) throw new NumberFormatException();
            return value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new FinanceException(400, "Valor em reais inválido. Use, por exemplo, 1500.50.");
        }
    }
}
