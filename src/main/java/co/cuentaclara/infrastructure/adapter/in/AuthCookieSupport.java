package co.cuentaclara.infrastructure.adapter.in;

import co.cuentaclara.infrastructure.security.jwt.JwtTokenService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class AuthCookieSupport {

    private AuthCookieSupport() {
    }

    public static void attachAuthCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        attachAuthCookie(response, token, maxAgeSeconds, false);
    }

    public static void attachAuthCookie(
            HttpServletResponse response,
            String token,
            long maxAgeSeconds,
            boolean forceSecure
    ) {
        boolean secureRequest = forceSecure;
        if (!secureRequest) {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttrs) {
                var request = servletAttrs.getRequest();
                String forwardedProto = request.getHeader("X-Forwarded-Proto");
                secureRequest = request.isSecure() || "https".equalsIgnoreCase(forwardedProto);
            }
        }
        ResponseCookie cookie = ResponseCookie.from(JwtTokenService.AUTH_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secureRequest)
                .path("/")
                .maxAge(maxAgeSeconds)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
