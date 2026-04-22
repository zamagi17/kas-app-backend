package com.zamagi.kas.controller;

import com.zamagi.kas.model.Budget;
import com.zamagi.kas.model.User;
import com.zamagi.kas.repository.BudgetRepository;
import com.zamagi.kas.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController
@RequestMapping("/api/budget")
public class BudgetController {

    @Autowired
    private BudgetRepository budgetRepository;
    
    @Autowired
    private UserRepository userRepository;

    private User getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User tidak ditemukan"));
    }

    // GET /api/budget?bulan=2025-07
    @GetMapping
    public ResponseEntity<List<Budget>> getAll(
            Authentication auth,
            @RequestParam(defaultValue = "") String bulan) {

        String username = auth.getName();
        List<Budget> list;

        if (bulan.isBlank()) {
            list = budgetRepository.findAll().stream()
                    .filter(b -> b.getUser() != null && b.getUser().getUsername().equals(username))
                    .toList();
        } else {
            list = budgetRepository.findByUserUsernameAndBulan(username, bulan);
        }
        return ResponseEntity.ok(list);
    }

    // POST /api/budget
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Budget budget, Authentication auth) {
        User user = getCurrentUser();

        if (budgetRepository.findByUserUsernameAndKategoriAndBulan(
                user.getUsername(), budget.getKategori(), budget.getBulan()).isPresent()) {
            return ResponseEntity.badRequest()
                    .body("Budget untuk kategori ini di bulan tersebut sudah ada.");
        }

        budget.setUser(user);
        return ResponseEntity.ok(budgetRepository.save(budget));
    }

    // PUT /api/budget/{id}
    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @RequestBody Budget updated,
            Authentication auth) {

        return budgetRepository.findById(id)
                .filter(b -> b.getUser() != null && b.getUser().getUsername().equals(auth.getName()))
                .map(b -> {
                    b.setLimitBulan(updated.getLimitBulan());
                    b.setCatatan(updated.getCatatan());
                    return ResponseEntity.ok(budgetRepository.save(b));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // DELETE /api/budget/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication auth) {
        return budgetRepository.findById(id)
                .filter(b -> b.getUser() != null && b.getUser().getUsername().equals(auth.getName()))
                .map(b -> {
                    budgetRepository.delete(b);
                    return ResponseEntity.ok(Map.of("message", "Budget dihapus"));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}