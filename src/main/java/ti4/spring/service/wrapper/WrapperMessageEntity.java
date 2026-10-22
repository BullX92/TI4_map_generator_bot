package ti4.spring.service.wrapper;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "wrapper_offered_message", indexes = @Index(name = "wrapper_message_channel", columnList = "channel_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
class WrapperMessageEntity {
    @Id
    private String messageId;

    @Column(name = "channel_id", nullable = false)
    private String channelId;

    @Column(nullable = false)
    private String gameName;

    private boolean thread;
}
