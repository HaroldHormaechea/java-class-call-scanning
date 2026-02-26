package com.hhg.benchmark.repository;

import com.hhg.benchmark.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByActive(boolean active);

    List<Category> findByParentIsNull();

    List<Category> findByParent(Category parent);

    Optional<Category> findByName(String name);
}
