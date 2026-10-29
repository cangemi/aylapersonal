package ayla.finance;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Reject fractional JSON numbers and strings instead of silently coercing money. */
public class CentsDeserializer extends ValueDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken()!=JsonToken.VALUE_NUMBER_INT)
            return context.reportInputMismatch(Long.class,"amountCents deve ser um número inteiro JSON em centavos.");
        return parser.getLongValue();
    }
}
