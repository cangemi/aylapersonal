package ayla.investment;

import static ayla.investment.PortfolioData.*;
import ayla.chat.session.ChatIdentity;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/investments")
public class PortfolioController {
    private final PortfolioService service;
    private final ChatIdentity identity;
    public PortfolioController(PortfolioService service,ChatIdentity identity){this.service=service;this.identity=identity;}
    @GetMapping("/portfolio") public Portfolio portfolio(Principal p){return service.portfolio(identity.resolve(p));}
    @GetMapping("/analytics") public InvestmentAnalytics.Dashboard analytics(Principal p,@RequestParam(defaultValue="0") int year){return service.analytics(identity.resolve(p),year);}
    @GetMapping("/positions/{id}") public View get(Principal p,@PathVariable UUID id){return service.get(identity.resolve(p),id);}
    @PostMapping("/positions") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public View create(Principal p,@Valid @RequestBody Input input,@RequestHeader(value="Idempotency-Key",required=false) String key){return service.create(identity.resolve(p),input,key);}
    @PutMapping("/positions/{id}") public View update(Principal p,@PathVariable UUID id,@Valid @RequestBody Input input){return service.update(identity.resolve(p),id,input);}
    @DeleteMapping("/positions/{id}") public void delete(Principal p,@PathVariable UUID id,@RequestParam long version){service.delete(identity.resolve(p),id,version);}
    @PostMapping("/positions/{id}/events") public View event(Principal p,@PathVariable UUID id,@Valid @RequestBody EventInput input){return service.addEvent(identity.resolve(p),id,input);}
    @PutMapping("/positions/{id}/events/{eventId}") public View updateEvent(Principal p,@PathVariable UUID id,@PathVariable UUID eventId,@Valid @RequestBody EventInput input){return service.updateEvent(identity.resolve(p),id,eventId,input);}
    @DeleteMapping("/positions/{id}/events/{eventId}") public View deleteEvent(Principal p,@PathVariable UUID id,@PathVariable UUID eventId,@RequestParam long version){return service.deleteEvent(identity.resolve(p),id,eventId,version);}
}
