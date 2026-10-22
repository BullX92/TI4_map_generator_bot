package ti4.spring.service.wrapper;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "wrapper_message_channel_cursor")
@Getter
@NoArgsConstructor
@AllArgsConstructor
class WrapperChannelEntity {
    @Id
    private String channelId;

    private long lastMessageId;
}
