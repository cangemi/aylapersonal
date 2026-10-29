package ayla.hermes;

import java.math.BigDecimal;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;

public record VoiceSettings(
        @NotNull @Pattern(regexp="pt-BR-(FranciscaNeural|AntonioNeural|ThalitaMultilingualNeural)")
        @Schema(example="pt-BR-FranciscaNeural", description="Voz obtida em GET /voice/voices.") String voice,
        @NotNull @DecimalMin("0.75") @DecimalMax("1.50") @Digits(integer=1,fraction=2)
        @Schema(example="1.02", description="Velocidade: 1.00 é normal; entre 0.75 e 1.50.") BigDecimal speed,
        @NotNull @Min(-50) @Max(50)
        @Schema(example="2", description="Variação de tom em Hz. Zero é o tom original; entre -50 e 50.") Integer pitchHz) {
    public String edgePitch() { return (pitchHz >= 0 ? "+" : "") + pitchHz + "Hz"; }
}
