package pl.pollub.pari.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.pollub.pari.user.domain.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, java.util.UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmail(@Param("email") String email);

    boolean existsByPhoneHash(String phoneHash);

    org.springframework.data.domain.Page<User> findByStatusOrderByLastNameAscFirstNameAsc(
            pl.pollub.pari.user.domain.UserStatus status, org.springframework.data.domain.Pageable pageable);

    /** Active accounts with the SUPPORT or ADMIN role, for the assignment list (PARI-83). */
    @Query("""
            select distinct u from User u
            where (:support member of u.roles or :admin member of u.roles) and u.status = pl.pollub.pari.user.domain.UserStatus.ACTIVE
            order by u.lastName, u.firstName
            """)
    java.util.List<User> findActiveStaff(@Param("support") pl.pollub.pari.user.domain.Role support,
                                         @Param("admin") pl.pollub.pari.user.domain.Role admin);

    boolean existsByPhoneHashAndIdNot(String phoneHash, java.util.UUID id);

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean existsByEmail(@Param("email") String email);
}
