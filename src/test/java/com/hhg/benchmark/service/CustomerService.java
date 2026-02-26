package com.hhg.benchmark.service;

import com.hhg.benchmark.entity.Customer;
import com.hhg.benchmark.entity.User;
import com.hhg.benchmark.exception.NotFoundException;
import com.hhg.benchmark.exception.ValidationException;
import com.hhg.benchmark.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final UserService userService;

    public CustomerService(CustomerRepository customerRepository, UserService userService) {
        this.customerRepository = customerRepository;
        this.userService = userService;
    }

    @Transactional
    public Customer register(Long userId, String firstName, String lastName,
                             String phone, String address) {
        if (firstName == null || firstName.isBlank()) {
            throw new ValidationException("First name must not be blank");
        }
        if (lastName == null || lastName.isBlank()) {
            throw new ValidationException("Last name must not be blank");
        }
        User user = userService.findById(userId);
        if (customerRepository.findByUser(user).isPresent()) {
            throw new ValidationException("Customer already registered for this user");
        }
        Customer customer = new Customer();
        customer.setUser(user);
        customer.setFirstName(firstName);
        customer.setLastName(lastName);
        customer.setPhone(phone);
        customer.setAddress(address);
        customer.setCreatedAt(LocalDateTime.now());
        return customerRepository.save(customer);
    }

    @Transactional
    public Customer updateProfile(Long id, String phone, String address) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer", id));
        if (phone != null) {
            customer.setPhone(phone);
        }
        if (address != null) {
            customer.setAddress(address);
        }
        return customerRepository.save(customer);
    }

    public Customer findById(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer", id));
    }

    public List<Customer> findAll() {
        return customerRepository.findAll();
    }

    public Customer findByUserId(Long userId) {
        return customerRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Customer for user", userId));
    }

    public Customer findByEmail(String email) {
        User user = userService.findActiveUsers().stream()
                .filter(u -> u.getEmail().equals(email))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("User with email: " + email));
        return findByUserId(user.getId());
    }
}
