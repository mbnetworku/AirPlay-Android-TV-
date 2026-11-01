package com.github.serezhka.airplay.server.discovery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Registers airplay/airtunes service mdns
 */
@Slf4j
@RequiredArgsConstructor
public class MdnsAdvertiser {

    private static final String AIRPLAY_SERVICE_TYPE = "._airplay._tcp.local";
    private static final String AIRTUNES_SERVICE_TYPE = "._raop._tcp.local";

    private final String serverName;
    private final boolean hlsEnabled;
    private final String pairingPublicKey;
    private final boolean mdnsLoopback;

    private final List<JmDNS> jmDNSList = new ArrayList<>();

    public void start(int airTunesPort) throws Exception {
        log.info("HLS {} features {} pk {}", hlsEnabled ? "enabled" : "disabled",
                AdvertisedReceiver.featuresTxt(hlsEnabled), pairingPublicKey);
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .filter(networkInterfaceFilter())
                .flatMap(ni -> java.util.Collections.list(ni.getInetAddresses()).stream())
                .filter(inetAddressFilter())
                .forEach(inetAddress -> {
                    try {
                        byte[] hardwareAddress = NetworkInterface.getByInetAddress(inetAddress).getHardwareAddress();
                        String mac = hardwareAddressBytesToString(hardwareAddress);

                        JmDNS jmDNS = JmDNS.create(inetAddress);
                        jmDNS.registerService(ServiceInfo.create(serverName + AIRPLAY_SERVICE_TYPE,
                                serverName, airTunesPort, 0, 0, airPlayMDNSProps(mac)));
                        String airTunesServerName = mac.replaceAll(":", "") + "@" + serverName;
                        jmDNS.registerService(ServiceInfo.create(airTunesServerName + AIRTUNES_SERVICE_TYPE,
                                airTunesServerName, airTunesPort, 0, 0, airTunesMDNSProps()));
                        // Loopback registrations are noise at INFO; LAN address is the discovery path.
                        if (inetAddress.isLoopbackAddress()) {
                            log.debug("{} / {} registered on {}, port {}",
                                    serverName + AIRPLAY_SERVICE_TYPE,
                                    airTunesServerName + AIRTUNES_SERVICE_TYPE,
                                    inetAddress.getHostAddress(), airTunesPort);
                        } else {
                            log.info("{} registered on {}, port {}",
                                    serverName + AIRPLAY_SERVICE_TYPE,
                                    inetAddress.getHostAddress(), airTunesPort);
                        }

                        jmDNSList.add(jmDNS);
                    } catch (IOException e) {
                        log.error(e.getMessage());
                    }
                });
    }

    public void stop() {
        for (final JmDNS jmDNS : jmDNSList) {
            jmDNS.unregisterAllServices();
            try {
                jmDNS.close();
            } catch (IOException e) {
                log.warn(e.getMessage());
            }
        }
        jmDNSList.clear();
    }

    private Map<String, String> airPlayMDNSProps(String deviceId) {
        HashMap<String, String> airPlayMDNSProps = new HashMap<>();
        airPlayMDNSProps.put("deviceid", deviceId);
        airPlayMDNSProps.put("features", AdvertisedReceiver.featuresTxt(hlsEnabled));
        airPlayMDNSProps.put("srcvers", AdvertisedReceiver.SOURCE_VERSION);
        airPlayMDNSProps.put("flags", AdvertisedReceiver.MDNS_FLAGS);
        airPlayMDNSProps.put("vv", Integer.toString(AdvertisedReceiver.VV));
        airPlayMDNSProps.put("model", AdvertisedReceiver.MODEL);
        airPlayMDNSProps.put("rhd", "5.6.0.0");
        airPlayMDNSProps.put("pw", "false");
        airPlayMDNSProps.put("pk", pairingPublicKey);
        return airPlayMDNSProps;
    }

    private Map<String, String> airTunesMDNSProps() {
        HashMap<String, String> airTunesMDNSProps = new HashMap<>();
        airTunesMDNSProps.put("ch", "2");
        airTunesMDNSProps.put("cn", "1,3");
        airTunesMDNSProps.put("da", "true");
        airTunesMDNSProps.put("et", "0,3,5");
        airTunesMDNSProps.put("ek", "1");
        airTunesMDNSProps.put("ft", AdvertisedReceiver.featuresTxt(hlsEnabled));
        airTunesMDNSProps.put("am", AdvertisedReceiver.MODEL);
        airTunesMDNSProps.put("md", "0,1,2");
        airTunesMDNSProps.put("sr", "44100");
        airTunesMDNSProps.put("ss", "16");
        airTunesMDNSProps.put("sv", "false");
        airTunesMDNSProps.put("sm", "false");
        airTunesMDNSProps.put("tp", "UDP");
        airTunesMDNSProps.put("txtvers", "1");
        airTunesMDNSProps.put("sf", AdvertisedReceiver.MDNS_FLAGS);
        airTunesMDNSProps.put("vs", AdvertisedReceiver.SOURCE_VERSION);
        airTunesMDNSProps.put("vn", "65537");
        airTunesMDNSProps.put("pk", pairingPublicKey);
        return airTunesMDNSProps;
    }

    private Predicate<NetworkInterface> networkInterfaceFilter() {
        return networkInterface -> {
            try {
                if (!networkInterface.isUp() || networkInterface.isPointToPoint()) {
                    return false;
                }
                if (networkInterface.isLoopback()) {
                    return mdnsLoopback;
                }
                return true;
            } catch (SocketException e) {
                return false;
            }
        };
    }

    private Predicate<InetAddress> inetAddressFilter() {
        return inetAddress -> inetAddress instanceof Inet4Address /*|| inetAddress instanceof Inet6Address*/;
    }

    private String hardwareAddressBytesToString(byte[] mac) {
        if (mac == null || mac.length != 6 || (deviceIdOverride != null && (mac[0] & 0x02) != 0)) {
            return fallbackMac(); // loopback, or Android 11+ which hides the real MAC
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? ":" : ""));
        }
        return sb.toString().toUpperCase();
    }

    /** Stable device id supplied by the host app when the OS hides the interface MAC. */
    public static volatile String deviceIdOverride;

    private String fallbackMac() {
        String id = deviceIdOverride;
        return id != null ? id : "00:00:00:00:00:00";
    }
}
