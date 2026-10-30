package com.grandvista.authservice.repository;

import com.grandvista.authservice.entity.Role;
import com.grandvista.authservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(Role role);

    /** Active (not soft-deleted) administrators, for the last-admin guard on delete. */
    long countByRoleAndDeletedAtIsNull(Role role);

    /** Admin list: soft-deleted accounts are hidden. */
    java.util.List<User> findAllByDeletedAtIsNull(org.springframework.data.domain.Sort sort);
}
