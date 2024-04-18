package shinzo.cineffi.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import shinzo.cineffi.domain.entity.user.User;
import shinzo.cineffi.domain.enums.LoginType;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByNickname(String nickname);
    @Query("SELECT u FROM User u JOIN u.userAccount ua WHERE ua.loginType = :loginType AND ua.email = :email")
    Optional<User> findUserForJoin(LoginType loginType, String email);
}
