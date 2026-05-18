package com.ssau.project.ssaupe.repository;

import com.ssau.project.ssaupe.model.SystemUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


@Repository
public interface UserRepository extends JpaRepository<SystemUser, Long>{

    SystemUser findByUsername(String username);
}
