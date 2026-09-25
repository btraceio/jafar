package io.jafar.mcp.lifecycle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BearerAuthFilter}. */
class BearerAuthFilterTest {

  private static final String TOKEN = "s3cr3t-token-value";

  @Test
  void rejectsRequestWithNoAuthorizationHeader() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    new BearerAuthFilter(TOKEN).doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void rejectsRequestWithWrongToken() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(request.getHeader("Authorization")).thenReturn("Bearer wrong-token");
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    new BearerAuthFilter(TOKEN).doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void rejectsRequestWithNonBearerScheme() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(request.getHeader("Authorization")).thenReturn("Basic " + TOKEN);
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    new BearerAuthFilter(TOKEN).doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void allowsRequestWithCorrectToken() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(request.getHeader("Authorization")).thenReturn("Bearer " + TOKEN);

    new BearerAuthFilter(TOKEN).doFilter(request, response, chain);

    verify(chain, times(1)).doFilter(request, response);
    verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  void tokenComparisonIsExact() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    // Prefix match must not be accepted as equal.
    when(request.getHeader("Authorization")).thenReturn("Bearer " + TOKEN + "extra");
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    new BearerAuthFilter(TOKEN).doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void rejectsShorterCandidateToken() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(request.getHeader("Authorization")).thenReturn("Bearer short");
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    new BearerAuthFilter(TOKEN).doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(request, response);
  }
}
