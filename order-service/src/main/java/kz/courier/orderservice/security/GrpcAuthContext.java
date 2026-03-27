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
}
