package ayla.config;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public final class LanAddress {
    private LanAddress() {}

    public static InetAddress detect() {
        try (var socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {
                    (byte) 192, 0, 2, 1
            }), 9));
            var address = socket.getLocalAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress())
                throw new IllegalStateException("A interface de rede não tem um endereço LAN acessível.");
            return address;
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível detectar o IP da rede local.", e);
        }
    }
}
