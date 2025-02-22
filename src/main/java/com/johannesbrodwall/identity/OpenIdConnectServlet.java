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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

public class OpenIdConnectServlet extends HttpServlet {

    private static Logger logger = LoggerFactory.getLogger(OpenIdConnectServlet.class);
    private final String providerName;
    private final Optional<String> openIdIssuerUrl;
    private Optional<String> consoleUrl;

    public OpenIdConnectServlet(String providerName, String openIdIssuerUrl, String consoleUrl) {
        this.providerName = providerName;
        this.openIdIssuerUrl = Optional.ofNullable(openIdIssuerUrl);
        this.consoleUrl = Optional.ofNullable(consoleUrl);
    }

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
            } else if (action.equals("refresh")) {
                refreshAccessToken(req, oauth2Configuration);
            } else if (action.equals("verify")) {
                verifyIdToken(req, resp);
            } else if (action.equals("logout")) {
                logoutSession(req, resp);
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
                    + "<h2>Setup error with provider <code>" + providerName + "</code></h2>"
                    + "<div><code>" + e.getMessage() + "</code></div>"
                    +  consoleUrl
                        .map(url ->
                                "<h2><a target='_blank' href='"  + url + "'>Setup " + providerName + "</a></h2>"
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

    private void authenticate(HttpServletRequest req, HttpServletResponse resp, Oauth2Configuration configuration) throws IOException {
        String loginState = UUID.randomUUID().toString();
        req.getSession().setAttribute("loginState", loginState);

        URL authorizationEndpoint = null;
        String clientId = null;
        String redirectUri = getRedirectUri(req);
        String scope = null;

        URL authenticationRequest = new URL(authorizationEndpoint + "?...&x=y&p=q...");

        logger.debug("Generating authentication request: {}", authenticationRequest);

        resp.setContentType("text/html");

        resp.getWriter().write("<!DOCTYPE html>\n"
                        + "<html>"
                        + "<head>"
                        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                        + "</head>"
                        + "<body>"
                        + "<h2>Step 1: Redirect to authorization endpoint</h2>"
                        + "<div><a href='" + authenticationRequest + "'>authenticate at " + authorizationEndpoint + "</a></div>"
                        + "<div>"
                        + "Normally your app would redirect directly to the following URL: <br />"
                        + "<code>"
                        + authenticationRequest.toString().replaceAll("[?&]", "<br />&nbsp;&nbsp;&nbsp;&nbsp;$0")
                        + "</code>"
                        + "</div></body></html>");
    }

    private Oauth2Configuration getOauth2Configuration() throws IOException {
        Configuration configuration = new Configuration(new File("oauth2-providers.properties"));
        return new Oauth2Configuration(
                getIssuerConfig(),
                new Oauth2ClientConfiguration(providerName, configuration)
        );
    }

    private OpenidConfiguration getIssuerConfig() throws IOException {
        Configuration configuration = new Configuration(new File("oauth2-providers.properties"));
        String openIdIssuerUrl = this.openIdIssuerUrl
                .orElseGet(() -> configuration.getRequiredProperty(providerName + ".issuer_uri"));
        return new OpenidConfiguration(openIdIssuerUrl);
    }

    private void oauth2callback(HttpServletRequest req, HttpServletResponse resp, Oauth2Configuration configuration) throws IOException {
        URL tokenEndpoint = configuration.getTokenEndpoint();

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

        String error = req.getParameter("error");
        if (error != null) {
            resp.setContentType("text/html");

            String errorDescription = req.getParameter("error_description");

            resp.getWriter().write("<!DOCTYPE html>\n"
                    + "<html>"
                    + "<head>"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                    + "</head>"
                    + "<body>"
                    + "<h2>Step 2b: Client received callback with error!</h2>"
                    + "<div>Error: <code>" + error + "</code></div>"
                    + (errorDescription != null ? "<div>Error description: <code>" + errorDescription + "</code></div>" : "")
                    + "<div><a href='/'>Front page</a></div>"
                    + "</body>"
                    + "</html>");
            return;
        }

        getToken(req, resp, configuration);
    }

    private void getToken(HttpServletRequest req, HttpServletResponse resp, Oauth2Configuration configuration) throws IOException {
        String code = req.getParameter("code");

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
        JsonObject jsonObject = JsonObject.parse(response);
        resp.setContentType("text/html");

        resp.getWriter().write("<!DOCTYPE html>\n"
                + "<html>"
                + "<head>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "</head>"
                + "<body>"
                + "<h2>Step 3: Process token</h2>"
                + "<div>This was the response from " + tokenEndpoint + "</div>"
                + "<pre>" + response + "</pre>"
                + jsonObject.stringValue("id_token")
                    .map(idToken -> "<button onclick='navigator.clipboard.writeText(\"" + idToken + "\").then(console.log)'>Copy id_token to clipboard</button>")
                    .orElse("")
                + "<div>Normally you application will directly use the token to establish an application session</div>"
                + "<div><a href='" + req.getServletPath() + "/session'>Create session</a></div>"
                + "<div><a href='/'>Front page</a></div>"
                + "</body>"
                + "</html>");
    }

    private void setupSession(HttpServletRequest req, HttpServletResponse resp, Oauth2Configuration configuration) throws IOException {
        OpenidConfiguration issuerConfig = getIssuerConfig();
        String clientId = configuration.getClientId();

        JsonObject tokenResponse = JsonObject.parse((String) req.getSession().getAttribute("token_response"));

        String accessToken = null;
        String idToken = null;
        JwtToken idTokenJwt = new JwtToken(idToken, true);
        if (!clientId.equals(idTokenJwt.aud())) {
            logger.warn("Token was not intended for us! {} != {}", clientId, idTokenJwt.aud());
        }
        if (!issuerConfig.getIssuer().equals(idTokenJwt.iss())) {
            logger.warn("Token was not issued by expected OpenID provider! {} != {}", issuerConfig.getIssuer(), idTokenJwt.iss());
        }

        UserSession.OpenIdConnectSession session = new UserSession.OpenIdConnectSession();
        session.setControlUrl(req.getServletPath());
        session.setAccessToken(accessToken);
        session.setRefreshToken(tokenResponse.stringValue("refresh_token"));
        session.setIdToken(idTokenJwt);
        session.setEndSessionEndpoint(configuration.getEndSessionEndpoint());

        URL userinfoEndpoint = null;
        session.setUserinfo(jsonParserParseToObject(userinfoEndpoint, null));

        UserSession.getFromSession(req).addSession(session);
        resp.sendRedirect("/");
    }

    private void logoutSession(HttpServletRequest req, HttpServletResponse resp) {
        logger.debug("Logging out session {}", req.getQueryString());

        UserSession.getFromSession(req).removeSession(req.getServletPath());

        resp.setStatus(200);
    }

    private void refreshAccessToken(HttpServletRequest req, Oauth2Configuration configuration) throws IOException {
        String clientId = configuration.getClientId();
        String redirectUri = getRedirectUri(req);
        String clientSecret = configuration.getClientSecret();
        URL tokenEndpoint = configuration.getTokenEndpoint();

        UserSession session = UserSession.getFromSession(req);
        UserSession.IdProviderSession idProviderSession = session.getIdProviderSessions().stream()
                .filter(s -> s.getControlUrl().equals(req.getServletPath()))
                .findAny().orElseThrow(() -> new IllegalArgumentException("Can't refresh non-existing session"));

        String payload = "client_id=" + clientId
                + "&" + "client_secret=" + clientSecret
                + "&" + "redirect_uri=" + redirectUri
                + "&" + "refresh_token=" + idProviderSession.getRefreshToken()
                + "&" + "grant_type=" + "refresh_token";
        HttpURLConnection connection = (HttpURLConnection) tokenEndpoint.openConnection();
        connection.setDoOutput(true);
        connection.setDoInput(true);
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(payload.getBytes());
        }
        JsonObject jsonObject = JsonObject.parse(connection);
        logger.debug("Refreshed session: {}", jsonObject);

        idProviderSession.setAccessToken(jsonObject.requiredString("access_token"));
    }

    private void verifyIdToken(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        OpenidConfiguration issuerConfig = getIssuerConfig();
        JwtToken idTokenJwt = new JwtToken(req.getParameter("id_token"), false);
        try {
            idTokenJwt.safeVerifySignature();
        } catch (Exception e) {
            resp.getWriter().println("Signature failed: " + e);
            return;
        }
        try {
            idTokenJwt.verifyTimeValidity(Instant.now());
        } catch (Exception e) {
            resp.getWriter().println("Token time validity check failed: " + e);
            return;
        }

        if (!isMatchingIssuer(idTokenJwt, issuerConfig.getIssuer())) {
            resp.getWriter().println("Invalid issuer (was " + idTokenJwt.iss() + ", but wanted " + issuerConfig.getIssuer() + ")");
            return;
        }

        resp.getWriter().println("Token valid");
    }

    private boolean isMatchingIssuer(JwtToken jwt, String issuer) {
        if (issuer.contains("{tenantid}")) {
            issuer = issuer.replaceAll("\\{tenantid}", jwt.getPayload().requiredString("tid"));
        }
        return issuer.equals(jwt.iss());
    }

    private JsonObject jsonParserParseToObject(URL endpoint, HttpAuthorization authorization) throws IOException {
        logger.debug("Fetching from {}", endpoint);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        authorization.authorize(connection);
        JsonObject response = JsonObject.parse(connection);
        logger.debug("Response: {}", response);
        return response;
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
