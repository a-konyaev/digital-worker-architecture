package ru.akonyaev.digitalworker;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import ru.akonyaev.digitalworker.events.NotificationEvent;

@UtilityClass
@Slf4j
public class PluginFunctions {

    public NotificationEvent createUnattendedDeviceNotification(String deviceId, String zoneId) {
        var event = new NotificationEvent();
        event.setMessage(String.format("Unattended device %s in zone %s!", deviceId, zoneId));
        log.info("Created notification: {}", event.getMessage());
        return event;
    }

    public void logData(Object... args) {
        var sb = new StringBuilder();
        for (Object arg : args) {
            sb.append(arg).append("; ");
        }
        log.info(sb.toString());
    }
}
