package com.hhg.benchmark.repository;

import com.hhg.benchmark.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByUsername(String username);

    List<User> findByActive(boolean active);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);
}
