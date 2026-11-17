package net.wdc.core.api.client;

import java.util.Optional;

public interface ClientApi {

    Optional<WDCCameraSnapshot> camera();

    Optional<WDCPlayerSnapshot> player();
}
