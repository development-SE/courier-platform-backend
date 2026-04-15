package kz.courier.apigateway.grpc;

/**
 * Immutable carrier of per-request authentication data extracted by the gateway
 * and passed explicitly to gRPC clients.
 */
public record AuthContext(String userId, String roles, String token, String companyId) {

    public static AuthContext of(String userId, String roles, String token, String companyId) {
        return new AuthContext(userId, roles, token, companyId);
    }

    public static AuthContext of(String userId, String roles, String token) {
        return new AuthContext(userId, roles, token, null);
    }
}
