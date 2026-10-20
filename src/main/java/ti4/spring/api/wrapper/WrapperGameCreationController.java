package ti4.spring.api.wrapper;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ti4.spring.context.RequestContext;

@RestController
@ConditionalOnProperty(name = "ti4.wrapper.enabled", havingValue = "true")
public class WrapperGameCreationController {
    public record CreationRequest(String requestId, String title, List<String> memberIds) {}

    private final WrapperGameCreationService creation;

    public WrapperGameCreationController(WrapperGameCreationService creation) {
        this.creation = creation;
    }

    @GetMapping("/api/wrapper/game-creation")
    public WrapperGameCreationService.Options options() {
        return creation.options(RequestContext.getUserId());
    }

    @PostMapping("/api/wrapper/games")
    public WrapperGameCreationService.Operation create(@RequestBody CreationRequest request) {
        return creation.create(RequestContext.getUserId(), request.requestId(), request.title(), request.memberIds());
    }

    @GetMapping("/api/wrapper/game-creation/{requestId}")
    public WrapperGameCreationService.Operation status(@PathVariable String requestId) {
        return creation.status(RequestContext.getUserId(), requestId);
    }
}
