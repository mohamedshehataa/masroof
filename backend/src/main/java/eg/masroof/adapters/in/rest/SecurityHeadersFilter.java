package eg.masroof.adapters.in.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** REQ-SEC-011: transport hardening headers on every backend response. */
final class SecurityHeadersFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    String path = request.getRequestURI();
    if (path.startsWith("/api/") || path.startsWith("/auth/")) {
      response.setHeader("Cache-Control", "no-store");
    }
    chain.doFilter(request, response);
  }
}
