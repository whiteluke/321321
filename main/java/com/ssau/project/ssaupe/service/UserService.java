
package com.ssau.project.ssaupe.service;


import com.ssau.project.ssaupe.model.SystemUser;

import java.util.List;


public interface UserService {
    void save(SystemUser client);

    SystemUser findByUsername(String username);
    List<SystemUser> findAllClients();
}
