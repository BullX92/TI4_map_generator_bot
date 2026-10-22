package ti4.spring.api.wrapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ti4.spring.context.RequestContext;
import ti4.spring.context.SetupRequestContext;

@RestController
@ConditionalOnProperty(name = "ti4.wrapper.enabled", havingValue = "true")
public class WrapperAgentController {
    public record StepRequest(String requestId) {}

    public record RunRequest(boolean enabled) {}

    private final WrapperAgentService agents;

    public WrapperAgentController(WrapperAgentService agents) {
        this.agents = agents;
    }

    @GetMapping("/api/wrapper/games/{gameName}/agents")
    @SetupRequestContext(false)
    public WrapperAgentService.View observe(@PathVariable String gameName) {
        return agents.observe(gameName, RequestContext.getUserId());
    }

    @PostMapping("/api/wrapper/games/{gameName}/agents/step")
    @SetupRequestContext(false)
    public WrapperAgentService.View step(@PathVariable String gameName, @RequestBody StepRequest request) {
        return agents.step(gameName, RequestContext.getUserId(), request.requestId());
    }

    @PostMapping("/api/wrapper/games/{gameName}/agents/run")
    @SetupRequestContext(false)
    public WrapperAgentService.View run(@PathVariable String gameName, @RequestBody RunRequest request) {
        return agents.running(gameName, RequestContext.getUserId(), request.enabled());
    }
}
