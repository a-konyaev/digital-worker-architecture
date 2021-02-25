package ru.akonyaev.digitalworker.events;

import lombok.Data;

@Data
public class PersonsInZoneEvent implements Event {
    private String zoneId;
    private int number;
}
