package org.thingai.app.meo.util;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HexFormat;

public class NetUtil {
    public static synchronized String lanMac() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress("8.8.8.8", 53));
            NetworkInterface nic = NetworkInterface.getByInetAddress(socket.getLocalAddress());
            byte[] mac = nic != null ? nic.getHardwareAddress() : null;
            return mac != null ? HexFormat.ofDelimiter(":").withUpperCase().formatHex(mac) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static synchronized String lanIpv4() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress("8.8.8.8", 53));
            InetAddress local = socket.getLocalAddress();
            if (local instanceof Inet4Address && !local.isLoopbackAddress() && !local.isAnyLocalAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception ignored) {
            // fall through to interface scan
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // no usable interface
        }
        return null;
    }
}
