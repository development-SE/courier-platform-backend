package kz.courier.orderservice.security;

import io.grpc.Context;

/**
 * Holds the gRPC {@link Context} key used to pass an {@link AuthenticatedUser}
 * from the {@link AuthInterceptor} into the service handler.
 *
 * <p>Usage in service method:
 * <pre>
 *   AuthenticatedUser user = GrpcAuthContext.AUTHENTICATED_USER_KEY.get();
 * </pre>
 */
public final class GrpcAuthContext {

    private GrpcAuthContext() {}

    /** The context key under which the authenticated user is stored. */
    public static final Context.Key<AuthenticatedUser> AUTHENTICATED_USER_KEY =
            Context.key("authenticatedUser");

    /** Role granted to delivery couriers. */
    public static final String ROLE_COURIER = "COURIER";
    /** Roles with platform-wide administrative privileges. */
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    /**
     * Returns {@code true} if the caller is a delivery courier.
     *
     * <p>Couriers are authorized in logistics-service against their concrete
     * assignments; order-service has no order&rarr;courier link of its own and
     * therefore trusts an authenticated COURIER to read the orders it is asked
     * about (its assignments).
     */
    public static boolean isCourier(AuthenticatedUser caller) {
        return caller != null && caller.hasRole(ROLE_COURIER);
    }

    /**
     * Returns {@code true} if the caller is a platform administrator
     * ({@code ADMIN} or {@code SUPER_ADMIN}) and may read every order
     * regardless of company or user ownership.
     */
    public static boolean isAdminOrSuperAdmin(AuthenticatedUser caller) {
        return caller != null && caller.hasRole(ROLE_ADMIN, ROLE_SUPER_ADMIN);
    }
}
