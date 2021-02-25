package ru.akonyaev.digitalworker;

import lombok.experimental.UtilityClass;
import ru.akonyaev.digitalworker.events.DeviceStatusEvent;
import ru.akonyaev.digitalworker.events.Event;
import ru.akonyaev.digitalworker.events.PersonsInZoneEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@UtilityClass
public class TopicEventConfig {
    private final Map<String, Class<? extends Event>> inputTopicToEventClassMap = new HashMap<>() {{
        put("persons-in-zone", PersonsInZoneEvent.class);
        put("device-status", DeviceStatusEvent.class);
    }};

    public Class<? extends Event> getEventClassByTopic(String topic) {
        var eventClass = inputTopicToEventClassMap.get(topic);
        if (eventClass == null) {
            throw new IllegalArgumentException("Unknown topic: " + topic);
        }
        return eventClass;
    }

    public Set<String> getInputTopics() {
        return inputTopicToEventClassMap.keySet();
    }

    public Set<String> getOutputTopics() {
        return Collections.singleton("notification");
    }
}
