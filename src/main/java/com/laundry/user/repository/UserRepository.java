package com.laundry.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.laundry.user.entity.User;

/**
 * Repository for User entity operations.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE r.name IN :roles ORDER BY u.createdAt DESC")
    List<User> findByRoleNames(@Param("roles") Collection<String> roles);

    @Query("SELECT COUNT(DISTINCT u) FROM User u JOIN u.roles r WHERE r.name = 'ADMIN' AND u.status = 'ACTIVE'")
    long countActiveAdmins();
}
