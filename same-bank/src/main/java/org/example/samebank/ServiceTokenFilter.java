package org.example.samebank;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
class ServiceTokenFilter extends OncePerRequestFilter {
    private final SameBankProps props;
    ServiceTokenFilter(SameBankProps props) { this.props = props; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getRequestURI().startsWith("/actuator")) { chain.doFilter(req, res); return; }
        String token = req.getHeader("X-Service-Token");
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                props.serviceToken().getBytes(StandardCharsets.UTF_8))) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"invalid or missing service token\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
