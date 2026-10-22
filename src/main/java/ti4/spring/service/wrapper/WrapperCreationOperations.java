package ti4.spring.service.wrapper;

import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class WrapperCreationOperations {
    private final WrapperCreationRepository repository;

    public WrapperCreationOperations(WrapperCreationRepository repository) {
        this.repository = repository;
    }

    public Optional<WrapperCreationOperation> find(String actorId, String requestId) {
        return repository.findById(actorId + ":" + requestId);
    }

    public boolean isReserved(String gameName) {
        return repository.existsByGameName(gameName);
    }

    public WrapperCreationOperation save(WrapperCreationOperation operation) {
        return repository.saveAndFlush(operation);
    }
}
