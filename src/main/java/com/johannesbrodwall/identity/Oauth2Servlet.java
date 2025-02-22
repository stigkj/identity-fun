package com.johannesbrodwall.identity;

import com.johannesbrodwall.identity.util.BearerToken;
import com.johannesbrodwall.identity.util.HttpAuthorization;
import org.jsonbuddy.JsonObject;
import org.jsonbuddy.parse.JsonHttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

public abstract class Oauth2Servlet extends HttpServlet {

    private static Logger logger = LoggerFactory.getLogger(Oauth2Servlet.class);

    private String grantType = "authorization_code";
    private String responseType = "code";

    protected abstract Optional<String> getConsoleUrl();

    protected abstract Oauth2Configuration getOauth2Configuration() throws IOException;

    protected abstract JsonObject fetchUserProfile(BearerToken accessToken) throws IOException;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] pathParts = req.getPathInfo().split("/");

        if (pathParts.length < 2) {
            resp.sendError(404);
            return;
        }

        try (MDC.MDCCloseable ignored = MDC.putCloseable("provider", req.getServletPath())) {
            String action = pathParts[1];
            Oauth2Configuration oauth2Configuration = getOauth2Configuration();
            if (action.equals("authenticate")) {
                authenticate(req, resp, oauth2Configuration);
            } else if (action.equals("oauth2callback")) {
                oauth2callback(req, resp, oauth2Configuration);
            } else if (action.equals("token")) {
                getToken(req, resp, oauth2Configuration);
            } else if (action.equals("session")) {
                setupSession(req, resp, oauth2Configuration);
            } else {
                logger.warn("Unknown request {}", req.getServletPath() + req.getPathInfo() + "?" + req.getQueryString());
                resp.sendError(404);
            }
        } catch (Oauth2ConfigurationException e) {
            logger.warn("Configuration problem", e);
            resp.getWriter().write("<!DOCTYPE html>\n"
                    + "<html>"
                    + "<head>"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                    + "</head>"
                    + "<body>"
                    + "<h2>Setup error with provider <code>" + getClass().getSimpleName() + "</code></h2>"
                    + "<div><code>" + e.getMessage() + "</code></div>"
                    +  getConsoleUrl()
                        .map(url ->
                                "<h2><a target='_blank' href='"  + url + "'>Setup " + getClass().getSimpleName() + "</a></h2>"
                                        + "<div>Use " +
                                        "<code>" + getRedirectUri(req) + "</code>" +
                                        " as redirect_uri " +
                                        "<button onclick='navigator.clipboard.writeText(\"" + getRedirectUri(req) + "\")'>clipboard</button>" +
                                        "</div>"
                        )
                        .orElse("")
                    + "<div><a href='/'>Front page</a></div>"
                    + "</body>"
                    + "</html>");
        } catch (Exception e) {
            logger.error("Error while handing request {}", req.getPathInfo(), e);
            resp.sendError(500, "Problems accessing " + req.getRequestURI() + ": " + e);
        }
    }

    private String getRedirectUri(HttpServletRequest req) {
        try {
            return getOauth2Configuration().getRedirectUri(getDefaultRedirectUri(req));
        } catch (IOException|Oauth2ConfigurationException e) {
            return getDefaultRedirectUri(req);
        }
    }

    private String getDefaultRedirectUri(HttpServletRequest req) {
        String scheme = Optional.ofNullable(req.getHeader("X-Forwarded-Proto")).orElse(req.getScheme());
        String host = Optional.ofNullable(req.getHeader("X-Forwarded-Host")).orElse(req.getHeader("Host"));
        return scheme + "://" + host + req.getContextPath() + req.getServletPath() + "/oauth2callback";
    }

    private void authenticate(HttpServletRequest req, HttpServletResponse resp, Oauth2Configuration oauth2Configuration) throws IOException {
        String loginState = UUID.randomUUID().toString();
        req.getSession().setAttribute("loginState", loginState);

        URL authorizationEndpoint = null;
        String clientId = null;
        String redirectUri = getRedirectUri(req);
        String scope = null;

        URL authenticationRequest = new URL(authorizationEndpoint + "?...&x=y&p=q...");

        logger.debug("Generating authentication request: {}", authenticationRequest);

        resp.setContentType("text/html");
        resp.getWriter().write("<html>" +
                "<h2>Step 1: Redirect to authorization endpoint</h2>" +
                "<div><a href='" + authenticationRequest + "'>authenticate at " + authorizationEndpoint + "</a></div>" +
                "<div>" +
                "Normally your app would redirect directly to the following URL: <br />" +
                "</code>" +
                "<code>" +
                authenticationRequest.toString().replaceAll("[?&]", "<br />&nbsp;&nbsp;&nbsp;&nbsp;$0") +
                "</div></html>");
    }

    private void oauth2callback(HttpServletRequest req, HttpServletResponse resp, Oauth2Configuration configuration) throws IOException {
        String code = req.getParameter("code");
        String state = req.getParameter("state");

        logger.debug("oauth2callback code {}", code);
        logger.debug("oauth2callback with response {}: {}", Collections.list(req.getParameterNames()), req.getQueryString());

        String loginState = (String) req.getSession().getAttribute("loginState");
        if (loginState == null) {
            logger.warn("Callback received without having called authorize first!");
        } else if (loginState.equals(state)) {
            logger.debug("Login state matches callback state: {}", state);
        } else {
            logger.warn("Login state DOES NOT match callback state: {} != {}", loginState, state);
        }

        getToken(req, resp, configuration);
    }

    private void getToken(HttpServletRequest req, HttpServletResponse resp, Oauth2Configuration configuration) throws IOException {
        URL tokenEndpoint = null;
        String payload = null;
        logger.debug("Fetching token from POST {} with payload: {}", tokenEndpoint, payload);
        HttpURLConnection connection = (HttpURLConnection) tokenEndpoint.openConnection();
        connection.setDoOutput(true);
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(payload.getBytes());
        }
        JsonHttpException.verifyResponseCode(connection);

        String response = toString(connection.getInputStream());

        logger.debug("Token response: {}", response);
        req.getSession().setAttribute("token_response", response);
        resp.setContentType("text/html");
        resp.getWriter().write("<html>"
                + "<h2>Step 3: Process token</h2>"
                + "<div>This was the response from " + tokenEndpoint + "</div>"
                + "<pre>" + response + "</pre>"
                + "<div>Normally you application will directly use the token to establish an application session</div>"
                + "<div><a href='" + req.getServletPath() + "/session'>Create session</a></div>"
                + "<div><a href='/'>Front page</a></div>"
                + "</html>");
    }

    private void setupSession(HttpServletRequest req, HttpServletResponse resp, Oauth2Configuration configuration) throws IOException {
        JsonObject tokenResponse = JsonObject.parse((String) req.getSession().getAttribute("token_response"));

        BearerToken accessToken = null;
        JsonObject profile = fetchUserProfile(accessToken);

        UserSession.Oauth2ProviderSession idpSession = new UserSession.Oauth2ProviderSession();
        idpSession.setControlUrl(req.getServletPath());
        idpSession.setIssuer(configuration.getAuthorizationEndpoint().getAuthority());
        idpSession.setAccessToken(accessToken.toString());
        idpSession.setUserinfo(profile);

        UserSession.getFromSession(req).addSession(idpSession);

        resp.sendRedirect("/");
    }

    JsonObject jsonParserParseToObject(URL endpoint, HttpAuthorization authorization) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        authorization.authorize(connection);
        return JsonObject.parse(connection);
    }

    private String toString(InputStream inputStream) throws IOException {
        StringBuilder responseBuffer = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            int c;
            while ((c = reader.read()) != -1) {
                responseBuffer.append((char) c);
            }
        }
        return responseBuffer.toString();
    }
}
