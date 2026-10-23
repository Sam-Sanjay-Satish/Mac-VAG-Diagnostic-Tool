package io.github.samsanjaysatish.macvagdiag.server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The web server listens on {@code 127.0.0.1} only. {@code application.properties} sets {@code server.address};
 * this customizer runs after Boot has applied it and refuses to start on anything that isn't a loopback address
 * (e.g. {@code --server.address=0.0.0.0}), so nobody on the network can reach the API.
 */
@Configuration(proxyBeanMethods = false)
class LoopbackOnlyConfiguration {

    static final byte[] IPV4_LOOPBACK = {127, 0, 0, 1};

    @Bean
    LoopbackOnlyCustomizer loopbackOnlyCustomizer() {
        return new LoopbackOnlyCustomizer();
    }

    static final class LoopbackOnlyCustomizer
            implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>, Ordered {

        @Override
        public void customize(ConfigurableServletWebServerFactory factory) {
            if (!(factory instanceof AbstractConfigurableWebServerFactory configurable)) {
                throw new IllegalStateException("Cannot check the bind address of " + factory.getClass().getName()
                        + "; refusing to start without a loopback-only server");
            }
            InetAddress address = configurable.getAddress();
            if (address == null) {
                configurable.setAddress(ipv4Loopback());
            } else if (!address.isLoopbackAddress()) {
                throw new IllegalStateException("server.address=" + address.getHostAddress() + " is not a loopback "
                        + "address. macvagdiag only listens on 127.0.0.1, so nothing on the network can reach it.");
            }
        }

        /** After Boot's own customizer, which applies {@code server.address}. */
        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress("localhost", IPV4_LOOPBACK);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e); // can't happen: four bytes are a valid IPv4 address
        }
    }
}
