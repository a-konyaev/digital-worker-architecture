package ru.akonyaev.digitalworker.events;

import lombok.Data;

@Data
public class DeviceStatusEvent implements Event {
    private String deviceId;
    private String zoneId;
    private boolean turnedOn;
}
