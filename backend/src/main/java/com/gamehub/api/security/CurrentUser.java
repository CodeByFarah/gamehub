package com.gamehub.api.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated principal into a controller method.
 *
 * <p>Exists so no controller ever reaches into SecurityContextHolder, and so
 * no endpoint accepts a user id as a parameter. An endpoint that takes the
 * caller identity from the request is one broken authorisation check away from
 * letting anyone read anyone else data, and that mistake is easy to make and
 * hard to spot in review. Taking it from the verified token instead makes the
 * mistake impossible to express.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
