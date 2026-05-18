package com.ssau.project.ssaupe.controller;

import com.ssau.project.ssaupe.model.SystemUser;
import com.ssau.project.ssaupe.repository.RoleRepository;
import com.ssau.project.ssaupe.repository.UserRepository;
import com.ssau.project.ssaupe.service.SecurityService;
import com.ssau.project.ssaupe.service.UserService;
import com.ssau.project.ssaupe.validator.UserValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.Collection;

@Controller
public class GeneralController {
    @Autowired
    private UserService userService;
    @Autowired
    private SecurityService securityService;
    @Autowired
    private UserValidator userValidator;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;

    @GetMapping({"/","/home"})
    public String showHomePage() {
        return "home";
    }

    @GetMapping("/home/about_devs")
    public String showAboutDevsPage() {
        return "/home/about_devs";
    }

    @GetMapping("/home/about_system")
    public String showAboutSystemPage() {
        return "/home/about_system";
    }


    @RequestMapping(value = "/login", method = RequestMethod.GET)
    public String login(Model model, String error, String logout) {

        if (error != null)
            model.addAttribute("error", "Your username and password is invalid.");

        return "login";
    }

    @GetMapping(value = "/registration")
    public String registration(Model model) {
        model.addAttribute("userForm", new SystemUser());
        return "registration";
    }

    @PostMapping(value = "/registration")
    public String registration(@ModelAttribute("userForm") SystemUser clientForm, BindingResult bindingResult, Model model) {
        userValidator.validate(clientForm, bindingResult);

        if (bindingResult.hasErrors()) {
            System.out.println("ERRORS");
            System.out.println(bindingResult.getAllErrors());
            return "registration";
        }

        clientForm.setRoles(roleRepository.findByName("USER"));
        userService.save(clientForm);

        securityService.autologin(clientForm.getUsername(), clientForm.getPasswordConfirm());

        return "redirect:/volunteer_home";
    }

    @GetMapping("/back_home")
    public String goBack(Authentication authentication, Model model) {
        System.out.println("Here");
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return "redirect:/";
        }
        System.out.println("Here2");
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();

        if (authorities.stream().anyMatch(a -> a.getAuthority().equals("ADMIN"))) {
            System.out.println("Here3");
            return "redirect:/organizer_home";
        } else if (authorities.stream().anyMatch(a -> a.getAuthority().equals("USER"))) {
            System.out.println("Here4");
            return "redirect:/volunteer_home";
        }
        System.out.println("Here5");
        return "redirect:/";
    }

    @RequestMapping(value = "/volunteer_home", method = RequestMethod.GET)
    public String volunteerHome(Model model) {
        return "volunteer_home";
    }
}
