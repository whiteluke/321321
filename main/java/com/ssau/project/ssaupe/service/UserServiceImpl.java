package com.ssau.project.ssaupe.service;


import com.ssau.project.ssaupe.model.SystemUser;
import com.ssau.project.ssaupe.repository.RoleRepository;
import com.ssau.project.ssaupe.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class UserServiceImpl implements UserService{
 
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void save(SystemUser client) {
//        client.setPassword(passwordEncoder.encode(client.getPassword()));
//        client.setRoles(new HashSet<>(roleRepository.findAll()));
        client.setPassword("{noop}" + client.getPassword());
        userRepository.save(client);
    }

    @Override
    public SystemUser findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Override
    public List<SystemUser> findAllClients() {
        return userRepository.findAll();
    }
}
