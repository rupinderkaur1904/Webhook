package dev.webhooks.platform.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a response field that can be {@code null}. The OpenAPI document (and the TypeScript types
 * generated from it) shows the field as {@code T | null}; every other response field is required
 * and non-null. Responses always include every field ({@code spring.jackson.default-property-inclusion: always}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface ApiNullable {}
