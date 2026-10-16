package ti4.spring.service.wrapper;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WrapperMessageRepository extends JpaRepository<WrapperMessageEntity, String> {
    List<WrapperMessageEntity> findByGameNameAndChannelIdOrderByMessageIdDesc(String gameName, String channelId);

    @Query("select distinct m.channelId from WrapperMessageEntity m where m.gameName = :gameName and m.thread = true")
    List<String> findChannelIds(@Param("gameName") String gameName);
}
