package com.fitbook.security;

import com.fitbook.dto.SessionUser;
import com.fitbook.exception.ForbiddenException;
import com.fitbook.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Role-based access control. Runs before every handler: if the target carries @RequiresRole, the
 * session must hold a logged-in user whose role matches. No session -> 401, wrong role -> 403
 * (both rendered by GlobalExceptionHandler).
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresRole required = handlerMethod.getMethodAnnotation(RequiresRole.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequiresRole.class);
        }
        if (required == null) {
            return true;
        }

        HttpSession session = request.getSession(false);
        SessionUser user = session == null ? null : (SessionUser) session.getAttribute(SessionKeys.CURRENT_USER);
        if (user == null) {
            throw new UnauthorizedException("Please log in to continue");
        }
        if (user.role() != required.value()) {
            throw new ForbiddenException("This page is only available to " + required.value().name().toLowerCase() + " accounts");
        }
        return true;
    }
}
