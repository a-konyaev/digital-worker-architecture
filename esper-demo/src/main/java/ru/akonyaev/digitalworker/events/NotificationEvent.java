package ru.akonyaev.digitalworker.events;

import lombok.Data;

@Data
public class NotificationEvent implements Event {
    private String message;
}
