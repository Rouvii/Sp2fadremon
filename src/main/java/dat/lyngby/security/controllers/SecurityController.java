package dat.lyngby.security.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEException;

import dat.lyngby.security.enums.Role;
import dat.lyngby.security.daos.ISecurityDAO;
import dat.lyngby.security.daos.SecurityDAO;
import dat.lyngby.security.entities.User;
import dat.lyngby.security.exceptions.ApiException;
import dat.lyngby.security.exceptions.NotAuthorizedException;
import dat.lyngby.security.exceptions.ValidationException;
import dat.lyngby.utils.Utils;



import dat.lyngby.security.controllers.ISecurityController;

import dk.bugelhartmann.ITokenSecurity;
import dk.bugelhartmann.TokenSecurity;
import dk.bugelhartmann.UserDTO;
import dat.lyngby.config.HibernateConfig;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;
import io.javalin.security.RouteRole;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;

import java.text.ParseException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Purpose: To handle security in the API
 * Author: Thomas Hartmann
 */
public class SecurityController implements ISecurityController {
    ObjectMapper objectMapper = new ObjectMapper();
    ITokenSecurity tokenSecurity = new TokenSecurity();
    private static ISecurityDAO securityDAO;
    private static SecurityController instance;
    private static Logger logger = getLogger(SecurityController.class);

    private SecurityController() { }


    public static SecurityController getInstance() { // Singleton because we don't want multiple instances of the same class
        if (instance == null) {
            instance = new SecurityController();
        }
        securityDAO = new SecurityDAO(HibernateConfig.getEntityManagerFactory());
        return instance;
    }

    @Override
    public Handler login() {
        return (ctx) -> {
            ObjectNode returnObject = objectMapper.createObjectNode(); // for sending json messages back to the client
            try {
                UserDTO user = ctx.bodyAsClass(UserDTO.class);
                UserDTO verifiedUser = securityDAO.getVerifiedUser(user.getUsername(), user.getPassword());
                String token = createToken(verifiedUser);

                ctx.status(200).json(returnObject
                        .put("token", token)
                        .put("username", verifiedUser.getUsername()));

            } catch (EntityNotFoundException | ValidationException e) {
                ctx.status(401);
                System.out.println(e.getMessage());
                ctx.json(returnObject.put("msg", e.getMessage()));
            }
        };
    }

    @Override
    public Handler register() {
        return (ctx) -> {
            ObjectNode returnObject = objectMapper.createObjectNode();
            try {
                UserDTO userInput = ctx.bodyAsClass(UserDTO.class);
                User created = securityDAO.createUser(userInput.getUsername(), userInput.getPassword());

                String token = createToken(new UserDTO(created.getUsername(), Set.of("USER")));
                ctx.status(HttpStatus.CREATED).json(returnObject
                        .put("token", token)
                        .put("username", created.getUsername()));
            } catch (EntityExistsException e) {
                ctx.status(HttpStatus.UNPROCESSABLE_CONTENT);
                logger.error("User already exists");
                ctx.json(returnObject.put("msg", "User already exists"));
            }
        };
    }

    @Override
    public Handler authenticate() {

        ObjectNode returnObject = objectMapper.createObjectNode();
        return (ctx) -> {
            // This is a preflight request => OK
            if (ctx.method().toString().equals("OPTIONS")) {
                ctx.status(200);
                return;
            }
            String header = ctx.header("Authorization");
            // If there is no token we do not allow entry
            if (header == null) {
                ctx.status(HttpStatus.FORBIDDEN).json(returnObject.put("msg", "Authorization header missing"));
                return;
            }
            String token = header.split(" ")[1];
            // If the Authorization Header was malformed = no entry
            if (token == null) {
                ctx.status(HttpStatus.FORBIDDEN).json(returnObject.put("msg", "Authorization header malformed"));
                return;
            }
            UserDTO verifiedTokenUser = verifyToken(token);
            if (verifiedTokenUser == null) {
                ctx.status(HttpStatus.FORBIDDEN).json(returnObject.put("msg", "Invalid User or Token"));
            }
            System.out.println("USER IN AUTHENTICATE: " + verifiedTokenUser);
            ctx.attribute("user", verifiedTokenUser); // -> ctx.attribute("user") in ApplicationConfig beforeMatched filter
        };
    }

    @Override
    // Check if the user's roles contain any of the allowed roles
    public boolean authorize(UserDTO user, Set<RouteRole> allowedRoles) {
        if (allowedRoles.isEmpty() || allowedRoles.contains(Role.ANYONE)) {
            return true;
        }
        if (user == null) {
            return false;
        }
        Set<String> roleNames = allowedRoles.stream()
                   .map(RouteRole::toString)  // Convert RouteRoles to Strings
                   .collect(Collectors.toSet());
        return user.getRoles().stream()
                   .map(String::toUpperCase)
                   .anyMatch(roleNames::contains);
        }

    @Override
    public String createToken(UserDTO user) {
        try {
            String ISSUER;
            String TOKEN_EXPIRE_TIME;
            String SECRET_KEY;

            if (System.getenv("DEPLOYED") != null) {
                ISSUER = System.getenv("ISSUER");
                TOKEN_EXPIRE_TIME = System.getenv("TOKEN_EXPIRE_TIME");
                SECRET_KEY = System.getenv("SECRET_KEY");
            } else {
                ISSUER = "Thomas Hartmann";
                TOKEN_EXPIRE_TIME = "1800000";
                SECRET_KEY = Utils.getPropertyValue("SECRET_KEY", "config.properties");
            }
            return tokenSecurity.createToken(user, ISSUER, TOKEN_EXPIRE_TIME, SECRET_KEY);
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Could not create token");
            throw new ApiException(500, "Could not create token");
        }
    }

    @Override
    public UserDTO verifyToken(String token) {
        boolean IS_DEPLOYED = (System.getenv("DEPLOYED") != null);
        String SECRET = IS_DEPLOYED ? System.getenv("SECRET_KEY") : Utils.getPropertyValue("SECRET_KEY", "config.properties");

        try {
            if (tokenSecurity.tokenIsValid(token, SECRET) && tokenSecurity.tokenNotExpired(token)) {
                return tokenSecurity.getUserWithRolesFromToken(token);
            } else {
                throw new NotAuthorizedException(403, "Token is not valid");
            }
        } catch (ParseException | JOSEException | NotAuthorizedException e) {
            e.printStackTrace();
            throw new ApiException(HttpStatus.UNAUTHORIZED.getCode(), "Unauthorized. Could not verify token");
        }
    }
}