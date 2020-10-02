/*
 * Copyright (c) 2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.asgardio.tomcat.saml.agent;

import org.opensaml.saml.saml2.core.LogoutResponse;
import io.asgardio.java.saml.sdk.SAML2SSOManager;
import io.asgardio.java.saml.sdk.bean.LoggedInSessionBean;
import io.asgardio.java.saml.sdk.bean.SSOAgentConfig;
import io.asgardio.java.saml.sdk.exception.InvalidSessionException;
import io.asgardio.java.saml.sdk.exception.SSOAgentException;
import io.asgardio.java.saml.sdk.util.SSOAgentConstants;
import io.asgardio.java.saml.sdk.util.SSOAgentFilterUtils;
import io.asgardio.java.saml.sdk.util.SSOAgentRequestResolver;
import io.asgardio.java.saml.sdk.util.SSOAgentUtils;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * Servlet Filter implementation class SAML2SSOAgentFilter.
 */
public class SAML2SSOAgentFilter implements Filter {

    private static final Logger LOGGER = Logger.getLogger(SSOAgentConstants.LOGGER_NAME);
    protected FilterConfig filterConfig = null;

    /**
     * @see Filter#init(FilterConfig)
     */
    @Override
    public void init(FilterConfig fConfig) throws ServletException {

        this.filterConfig = fConfig;
    }

    /**
     * @see Filter#doFilter(ServletRequest, ServletResponse, FilterChain)
     */
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        try {
            SSOAgentConfig ssoAgentConfig = SSOAgentFilterUtils.getSSOAgentConfig(filterConfig);

            SSOAgentRequestResolver resolver =
                    new SSOAgentRequestResolver(request, response, ssoAgentConfig);
            String indexPage = ssoAgentConfig.getIndexPage();
            SAML2SSOManager samlSSOManager;

            if (resolver.isSLORequest()) {

                samlSSOManager = new SAML2SSOManager(ssoAgentConfig);
                LogoutResponse logoutResponse = samlSSOManager.doSLO(request);
                String encodedRequestMessage = samlSSOManager.buildPostResponse(logoutResponse);
                SSOAgentUtils.sendPostResponse(request, response, encodedRequestMessage);
                response.sendRedirect(indexPage);
                return;
            } else if (resolver.isURLToSkip()) {
                chain.doFilter(servletRequest, servletResponse);
                return;

            } else if (request.getSession(false) != null) {

                if (resolver.isSAML2SSOResponse()) {
                    samlSSOManager = new SAML2SSOManager(ssoAgentConfig);
                    try {
                        samlSSOManager.processResponse(request, response);
                    } catch (SSOAgentException e) {
                        handleException(request, e);
                    }

                } else if (resolver.isSAML2ArtifactResponse()) {
                    samlSSOManager = new SAML2SSOManager(ssoAgentConfig);
                    try {
                        samlSSOManager.processArtifactResponse(request);
                    } catch (SSOAgentException e) {
                        handleException(request, e);
                    }
                } else if (resolver.isSLOURL()) {

                    samlSSOManager = new SAML2SSOManager(ssoAgentConfig);
                    if (resolver.isHttpPostBinding()) {

                        boolean isPassiveAuth = ssoAgentConfig.getSAML2().isPassiveAuthn();
                        ssoAgentConfig.getSAML2().setPassiveAuthn(false);
                        String htmlPayload = samlSSOManager.buildPostRequest(request, response, true);
                        ssoAgentConfig.getSAML2().setPassiveAuthn(isPassiveAuth);
                        SSOAgentUtils.sendPostResponse(request, response, htmlPayload);

                    } else {
                        //if "SSOAgentConstants.HTTP_BINDING_PARAM" is not defined, default to redirect
                        boolean isPassiveAuth = ssoAgentConfig.getSAML2().isPassiveAuthn();
                        ssoAgentConfig.getSAML2().setPassiveAuthn(false);
                        String redirectUrl = samlSSOManager.buildRedirectRequest(request, true);
                        ssoAgentConfig.getSAML2().setPassiveAuthn(isPassiveAuth);
                        response.sendRedirect(redirectUrl);
                    }
                    return;

                } else if (resolver.isSAML2SSOURL()) {
                    samlSSOManager = new SAML2SSOManager(ssoAgentConfig);
                    if (resolver.isHttpPostBinding()) {
                        String htmlPayload = samlSSOManager.buildPostRequest(request, response, false);
                        SSOAgentUtils.sendPostResponse(request, response, htmlPayload);
                        return;
                    }
                    response.sendRedirect(samlSSOManager.buildRedirectRequest(request, false));
                    return;

                } else if (resolver.isPassiveAuthnRequest()) {
                    samlSSOManager = new SAML2SSOManager(ssoAgentConfig);
                    boolean isPassiveAuth = ssoAgentConfig.getSAML2().isPassiveAuthn();
                    ssoAgentConfig.getSAML2().setPassiveAuthn(true);
                    String redirectUrl = samlSSOManager.buildRedirectRequest(request, false);
                    ssoAgentConfig.getSAML2().setPassiveAuthn(isPassiveAuth);
                    response.sendRedirect(redirectUrl);
                    return;
                }

                if (request.getSession(false).getAttribute(SSOAgentConstants.SESSION_BEAN_NAME) == null) {
                    request.getSession().invalidate();
                    response.sendRedirect(indexPage);
                    return;
                }

            }
            HttpSession session = request.getSession();
            LoggedInSessionBean
                    sessionBean = (LoggedInSessionBean) session.getAttribute(SSOAgentConstants.SESSION_BEAN_NAME);

            if (sessionBean == null || sessionBean.getSAML2SSO() == null) {
                response.sendRedirect(indexPage);
                return;
            }
            // pass the request along the filter chain
            chain.doFilter(request, response);

        } catch (InvalidSessionException e) {
            // Redirect to the index page when session is expired or user already logged out.
            LOGGER.log(Level.FINE, "Invalid Session!", e);
            response.sendRedirect(filterConfig.getServletContext().getContextPath());
        }
    }

    /**
     * @see Filter#destroy()
     */
    @Override
    public void destroy() {

        return;
    }

    protected void handleException(HttpServletRequest request, SSOAgentException e)
            throws SSOAgentException {

        if (request.getSession(false) != null) {
            request.getSession(false).removeAttribute(SSOAgentConstants.SESSION_BEAN_NAME);
        }
        throw e;
    }

}
