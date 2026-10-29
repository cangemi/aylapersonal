package ayla.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.Optional;

/** Informa o resultado real do JUnit, inclusive quando o teste espera uma exceção. */
public class TestResultLogger implements TestWatcher {

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[1;32m";
    private static final String RED = "\u001B[1;31m";
    private static final String YELLOW = "\u001B[1;33m";

    @Override
    public void testSuccessful(ExtensionContext context) {
        report("PASSOU", context);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        // Não imprime argumentos nem mensagens que possam conter credenciais.
        report("FALHOU", context);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        report("ABORTADO", context);
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        report("IGNORADO", context);
    }

    private void report(String result, ExtensionContext context) {
        String color = switch (result) {
            case "PASSOU" -> GREEN;
            case "FALHOU" -> RED;
            default -> YELLOW;
        };

        System.out.printf("[TESTE] %s[%s]%s %s > %s%n", color, result, RESET,
            context.getRequiredTestClass().getSimpleName(), context.getDisplayName());
    }
}
