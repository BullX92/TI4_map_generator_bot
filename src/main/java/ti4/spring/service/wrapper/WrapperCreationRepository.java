package ti4.spring.service.wrapper;

import org.springframework.data.jpa.repository.JpaRepository;

interface WrapperCreationRepository extends JpaRepository<WrapperCreationOperation, String> {
    boolean existsByGameName(String gameName);
}
