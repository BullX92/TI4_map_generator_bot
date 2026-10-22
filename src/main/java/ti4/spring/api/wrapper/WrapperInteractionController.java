package ti4.spring.api.wrapper;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ti4.spring.context.RequestContext;
import ti4.spring.context.SetupRequestContext;

@RestController
@ConditionalOnProperty(name = "ti4.wrapper.enabled", havingValue = "true")
@RequestMapping("/api/game/{gameName}/interactions")
public class WrapperInteractionController {

    public record ActionRequest(String requestId, List<String> values) {}

    public record ModalRequest(String requestId, Map<String, String> fields) {}

    private final WrapperInteractionService interactions;

    public WrapperInteractionController(WrapperInteractionService interactions) {
        this.interactions = interactions;
    }

    @GetMapping
    @SetupRequestContext(false)
    @PreAuthorize("@security.canAccessGame(#gameName)")
    public WrapperInteractionService.Observation get(@PathVariable String gameName) {
        return interactions.observe(gameName, RequestContext.getUserId());
    }

    @PostMapping("/{token}")
    @SetupRequestContext(false)
    @PreAuthorize("@security.canAccessGame(#gameName)")
    public WrapperInteractionService.ActionResult act(
            @PathVariable String gameName, @PathVariable String token, @RequestBody ActionRequest request) {
        return interactions.act(gameName, RequestContext.getUserId(), token, request.requestId(), request.values());
    }

    @PostMapping("/modals/{token}")
    @SetupRequestContext(false)
    @PreAuthorize("@security.canAccessGame(#gameName)")
    public WrapperInteractionService.ActionResult submitModal(
            @PathVariable String gameName, @PathVariable String token, @RequestBody ModalRequest request) {
        return interactions.submitModal(
                gameName, RequestContext.getUserId(), token, request.requestId(), request.fields());
    }
}
