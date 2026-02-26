package com.hhg.benchmark.service;

import com.hhg.benchmark.entity.Supplier;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.repository.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SupplierService {

    private final SupplierRepository supplierRepository;

    public SupplierService(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    @Transactional
    public Supplier register(String name, String email, String phone, String address) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Supplier name must not be blank");
        }
        if (email != null && supplierRepository.findByEmail(email).isPresent()) {
            throw new ValidationException("Supplier with email '" + email + "' already exists");
        }
        Supplier supplier = new Supplier();
        supplier.setName(name);
        supplier.setEmail(email);
        supplier.setPhone(phone);
        supplier.setAddress(address);
        supplier.setActive(true);
        supplier.setCreatedAt(LocalDateTime.now());
        return supplierRepository.save(supplier);
    }

    @Transactional
    public Supplier update(Long id, String name, String phone, String address) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Supplier", id));
        if (name != null && !name.isBlank()) {
            supplier.setName(name);
        }
        if (phone != null) {
            supplier.setPhone(phone);
        }
        if (address != null) {
            supplier.setAddress(address);
        }
        return supplierRepository.save(supplier);
    }

    @Transactional
    public void deactivate(Long id) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Supplier", id));
        supplier.setActive(false);
        supplierRepository.save(supplier);
    }

    public Supplier findById(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Supplier", id));
    }

    public List<Supplier> findAll() {
        return supplierRepository.findAll();
    }

    public List<Supplier> findActive() {
        return supplierRepository.findByActive(true);
    }

    public List<Supplier> search(String name) {
        return supplierRepository.findByNameContainingIgnoreCase(name);
    }
}
