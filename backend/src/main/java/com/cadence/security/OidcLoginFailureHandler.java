package com.cadence.security;

import com.cadence.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Maps any OIDC/OAuth2 sign-in failure (IdP unreachable, token error) to a generic
 * /login?error=idp_unavailable redirect with no exception detail or stack trace (FR-034/SEC-12).
 * The email+password fallback remains reachable.
 */
@Component
public class OidcLoginFailureHandler implements AuthenticationFailureHandler {

    private final AuthProperties props;

    public OidcLoginFailureHandler(AuthProperties props) {
        this.props = props;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        // Deliberately ignore the exception detail — never leak it to the client or logs.
        response.sendRedirect(props.getSpaBaseUrl() + "/login?error=idp_unavailable");
    }
}
