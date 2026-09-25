package io.jafar.mcp.lifecycle;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Rejects any request to the SSE daemon's servlet that does not present the expected {@code
 * Authorization: Bearer <token>} header. See {@link SseAuthToken} for how the token is generated
 * and where it is published to the local user.
 */
public final class BearerAuthFilter implements Filter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final String expectedToken;

  public BearerAuthFilter(String expectedToken) {
    this.expectedToken = expectedToken;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;

    String presented = extractToken(httpRequest.getHeader("Authorization"));
    if (presented == null || !constantTimeEquals(presented, expectedToken)) {
      httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      httpResponse.setContentType("text/plain; charset=utf-8");
      httpResponse.getWriter().write("Unauthorized: missing or invalid bearer token");
      return;
    }
    chain.doFilter(request, response);
  }

  private static String extractToken(String authorizationHeader) {
    if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
      return null;
    }
    return authorizationHeader.substring(BEARER_PREFIX.length()).trim();
  }

  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
