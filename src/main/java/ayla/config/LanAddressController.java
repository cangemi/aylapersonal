package ayla.config;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
public class LanAddressController {
    @GetMapping(value = "/internal/lan-ip", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getLanAddress() {
        return LanAddress.detect().getHostAddress();
    }
}
