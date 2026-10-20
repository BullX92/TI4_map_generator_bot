package ti4.spring.service.wrapper;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "wrapper_game_creation")
@Getter
@NoArgsConstructor
public class WrapperCreationOperation {
    @Id
    private String operationId;

    private String actorId;
    private String requestId;
    private String payloadHash;

    @Column(unique = true)
    private String gameName;

    private String instanceId;
    private String state;

    @Version
    private Long version;

    public WrapperCreationOperation(
            String operationId,
            String actorId,
            String requestId,
            String payloadHash,
            String gameName,
            String instanceId,
            String state) {
        this.operationId = operationId;
        this.actorId = actorId;
        this.requestId = requestId;
        this.payloadHash = payloadHash;
        this.gameName = gameName;
        this.instanceId = instanceId;
        this.state = state;
    }

    public WrapperCreationOperation withState(String state) {
        var updated =
                new WrapperCreationOperation(operationId, actorId, requestId, payloadHash, gameName, instanceId, state);
        updated.version = version;
        return updated;
    }
}
