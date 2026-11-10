package io.github.borohue.mustangobd;

import java.util.Collections;
import java.util.List;

/** Per-car app variant: name, default OBD protocol, enhanced profile, download folder, demo car. */
public final class Car {
    public final String id, appName, description, protocol, folder;
    public final List<Obd.Did> profile;

    private Car(String id, String appName, String description, String protocol, String folder,
                List<Obd.Did> profile) {
        this.id = id;
        this.appName = appName;
        this.description = description;
        this.protocol = protocol;
        this.folder = folder;
        this.profile = profile;
    }

    /** 2016 Mustang: CAN 11-bit 500k, Ford Mode 22 candidates. */
    public static final Car MUSTANG = new Car("mustang", "Mustang OBD", "2016 Ford Mustang 3.7 V6", "6",
            "MustangOBD", Obd.PROFILE);
    /** 2005 W211 E220 CDI: protocol auto-detected (K-line on pre-2006 cars), generic EOBD only. */
    public static final Car W211 = new Car("w211", "W211 OBD", "2005 Mercedes-Benz E220 CDI (W211)", "0",
            "W211OBD", Collections.<Obd.Did>emptyList());

    public static Car byId(String id) {
        return "w211".equals(id) ? W211 : MUSTANG;
    }
}
