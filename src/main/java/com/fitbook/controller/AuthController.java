package com.fitbook.controller;

import com.fitbook.dto.LoginRequest;
import com.fitbook.dto.SessionUser;
import com.fitbook.exception.InvalidCredentialsException;
import com.fitbook.model.Role;
import com.fitbook.security.SessionKeys;
import com.fitbook.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/** Page Controller for login/logout: establishes the server-side session. */
@Controller
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/login")
    public String loginForm(SessionUser currentUser) {
        return currentUser != null ? "redirect:" + landingPage(currentUser) : "login";
    }

    @PostMapping("/login")
    public String login(@Valid @ModelAttribute("loginRequest") LoginRequest form, BindingResult errors,
                        HttpServletRequest request, HttpServletResponse response, Model model) {
        if (errors.hasErrors()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            model.addAttribute("error", "Username and password are required");
            return "login";
        }
        try {
            SessionUser user = authService.login(form.username(), form.password());
            request.getSession(true);
            request.changeSessionId(); // new session id on login prevents session fixation
            request.getSession().setAttribute(SessionKeys.CURRENT_USER, user);
            return "redirect:" + landingPage(user);
        } catch (InvalidCredentialsException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            model.addAttribute("error", e.getMessage());
            return "login";
        }
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/";
    }

    private String landingPage(SessionUser user) {
        return user.role() == Role.PROVIDER ? "/provider" : "/browse";
    }
}
