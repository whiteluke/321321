package com.ssau.project.ssaupe.repository;

import com.ssau.project.ssaupe.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Set;


@Repository
public interface RoleRepository extends JpaRepository<Role, Long>{
    Set<Role> findByName(String roleName);
}
