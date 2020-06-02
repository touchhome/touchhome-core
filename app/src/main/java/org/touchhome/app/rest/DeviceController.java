package org.touchhome.app.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.touchhome.bundle.bluetooth.BluetoothService;

import java.util.Map;

@RestController
@RequestMapping("/rest/device")
@RequiredArgsConstructor
public class DeviceController {

    private final BluetoothService bluetoothService;

    @GetMapping("characteristic")
    public Map<String, String> getDeviceCharacteristics() {
        return bluetoothService.getDeviceCharacteristics();
    }

    @PutMapping("characteristic/{uuid}")
    public void setDeviceCharacteristic(@PathVariable("uuid") String uuid, @RequestBody byte[] value) {
        bluetoothService.setDeviceCharacteristic(uuid, value);
    }
}