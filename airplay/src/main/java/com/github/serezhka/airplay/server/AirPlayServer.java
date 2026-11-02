package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;
import com.github.serezhka.airplay.server.discovery.MdnsAdvertiser;
import com.github.serezhka.airplay.server.internal.ControlServer;

public class AirPlayServer {

    private final MdnsAdvertiser airPlayBonjour;
    private final ControlServer controlServer;

    public AirPlayServer(AirPlayConfig airPlayConfig, Playback airPlayConsumer) {
        this(airPlayConfig, airPlayConsumer, PairingIdentity.generate());
    }

    /** Pass the same {@link PairingIdentity} every time (see {@link PairingIdentity#fromSeed}) so a
     *  phone that already paired with this receiver keeps recognising it after a restart. */
    public AirPlayServer(AirPlayConfig airPlayConfig, Playback airPlayConsumer, PairingIdentity identity) {
        airPlayBonjour = new MdnsAdvertiser(airPlayConfig.getServerName(), airPlayConfig.isHlsEnabled(),
                identity.publicKeyHex(), airPlayConfig.isMdnsLoopback());
        controlServer = new ControlServer(airPlayConfig, airPlayConsumer, identity);
    }

    public void start() throws Exception {
        controlServer.start();
        airPlayBonjour.start(controlServer.getPort());
    }

    public void stop() {
        airPlayBonjour.stop();
        controlServer.stop();
    }
}
