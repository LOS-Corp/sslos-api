package com.laundry.user;

import com.laundry.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmail(String email);

    Optional<User> findByPhone(String phone);

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean emailTaken(String email);

    boolean existsByPhone(String phone);

    @Query("""
            select distinct u from User u left join u.roles r
            where (:role is null or r = :role)
              and (:q is null or lower(u.fullName) like lower(concat('%', :q, '%'))
                   or lower(u.email) like lower(concat('%', :q, '%')) or u.phone like concat('%', :q, '%'))
            """)
    Page<User> search(Role role, String q, Pageable pageable);
}
