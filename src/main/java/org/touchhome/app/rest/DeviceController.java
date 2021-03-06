package org.touchhome.app.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.*;
import org.touchhome.bundle.api.model.OptionModel;
import org.touchhome.bundle.bluetooth.BluetoothBundleEntryPoint;

import static org.touchhome.bundle.api.util.Constants.ADMIN_ROLE;

@RestController
@RequestMapping("/rest/device")
@RequiredArgsConstructor
public class DeviceController {

    private final BluetoothBundleEntryPoint bluetoothBundleEntrypoint;

    @GetMapping("/characteristic/{uuid}")
    public OptionModel getDeviceCharacteristic(@PathVariable("uuid") String uuid) {
        return OptionModel.key(bluetoothBundleEntrypoint.getDeviceCharacteristic(uuid));
    }

    @PutMapping("/characteristic/{uuid}")
    @Secured(ADMIN_ROLE)
    public void setDeviceCharacteristic(@PathVariable("uuid") String uuid, @RequestBody byte[] value) {
        bluetoothBundleEntrypoint.setDeviceCharacteristic(uuid, value);
    }
}
