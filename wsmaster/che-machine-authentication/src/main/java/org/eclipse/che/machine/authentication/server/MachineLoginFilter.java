package org.eclipse.che.machine.authentication.server;

import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.model.user.User;
import org.eclipse.che.api.user.server.UserManager;
import org.eclipse.che.commons.auth.token.RequestTokenExtractor;
import org.eclipse.che.commons.env.EnvironmentContext;
import org.eclipse.che.commons.subject.Subject;
import org.eclipse.che.commons.subject.SubjectImpl;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.security.Principal;

/**
 * @author Max Shaposhnik (mshaposhnik@codenvy.com)
 */
@Singleton
public class MachineLoginFilter implements Filter {

    @Inject
    private RequestTokenExtractor tokenExtractor;

    @Inject
    private MachineTokenRegistry machineTokenRegistry;

    @Inject
    private UserManager userManager;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        final HttpServletRequest httpRequest = (HttpServletRequest)servletRequest;
        if (httpRequest.getRequestURI().endsWith("/ws") || httpRequest.getRequestURI().endsWith("/eventbus")
            || httpRequest.getScheme().equals("ws") || httpRequest.getScheme().equals("wss") || httpRequest.getRequestURI().contains("/websocket/") ||
            tokenExtractor.getToken(httpRequest) == null || !tokenExtractor.getToken(httpRequest).startsWith("machine")) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        } else {
            String tokenString;
            User user;
            try {
                tokenString =  tokenExtractor.getToken(httpRequest);
                String userId = machineTokenRegistry.getUserId(tokenString);
                user = userManager.getById(userId);
            } catch (NotFoundException | ServerException e) {
                throw new ServletException("Cannot find user by machine token.");
            }

            final Subject subject =
                    new SubjectImpl(user.getName(), user.getId(), tokenString, false);

            try {
                EnvironmentContext.getCurrent().setSubject(subject);
                filterChain.doFilter(addUserInRequest(httpRequest, subject), servletResponse);
            } finally {
                EnvironmentContext.reset();
            }
        }
    }

    private HttpServletRequest addUserInRequest(final HttpServletRequest httpRequest, final Subject subject) {
        return new HttpServletRequestWrapper(httpRequest) {
            @Override
            public String getRemoteUser() {
                return subject.getUserName();
            }

            @Override
            public Principal getUserPrincipal() {
                return subject::getUserName;
            }
        };
    }

    @Override
    public void destroy() {

    }
}