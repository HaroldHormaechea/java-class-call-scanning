package com.hhg.benchmark.service;

import com.hhg.benchmark.entity.Category;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Transactional
    public Category createCategory(String name, String description, Long parentId) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Category name must not be blank");
        }
        Category parent = null;
        if (parentId != null) {
            parent = categoryRepository.findById(parentId)
                    .orElseThrow(() -> new NotFoundException("Category", parentId));
        }
        if (categoryRepository.findByName(name).isPresent()) {
            throw new ValidationException("Category with name '" + name + "' already exists");
        }
        Category category = new Category();
        category.setName(name);
        category.setDescription(description);
        category.setParent(parent);
        category.setActive(true);
        return categoryRepository.save(category);
    }

    @Transactional
    public Category updateCategory(Long id, String name, String description) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category", id));
        if (name != null && !name.isBlank()) {
            category.setName(name);
        }
        if (description != null) {
            category.setDescription(description);
        }
        return categoryRepository.save(category);
    }

    @Transactional
    public void deleteCategory(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category", id));
        category.setActive(false);
        categoryRepository.save(category);
    }

    public Category findById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category", id));
    }

    public List<Category> findAll() {
        return categoryRepository.findAll();
    }

    public List<Category> findActive() {
        return categoryRepository.findByActive(true);
    }

    public List<Category> findTopLevel() {
        return categoryRepository.findByParentIsNull();
    }

    public List<Category> findChildren(Long parentId) {
        Category parent = categoryRepository.findById(parentId)
                .orElseThrow(() -> new NotFoundException("Category", parentId));
        return categoryRepository.findByParent(parent);
    }
}
