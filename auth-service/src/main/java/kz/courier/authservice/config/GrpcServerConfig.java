package kz.courier.authservice.config;

import net.devh.boot.grpc.server.autoconfigure.*;
import org.springframework.context.annotation.*;

@Configuration
@Import(GrpcServerAutoConfiguration.class)
public class GrpcServerConfig {}
