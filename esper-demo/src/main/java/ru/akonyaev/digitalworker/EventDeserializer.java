package ru.akonyaev.digitalworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Deserializer;
import ru.akonyaev.digitalworker.events.Event;

import java.util.Map;

@Slf4j
public class EventDeserializer<T extends Event> implements Deserializer<T> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }

        try {
            var eventClass = TopicEventConfig.getEventClassByTopic(topic);
            //noinspection unchecked
            return (T) objectMapper.readValue(data, eventClass);
        } catch (Throwable e) {
            String eMsg = e.getMessage();
            String msg = String.format("Error of deserialization event from topic '%s': %s",
                    topic, eMsg == null ? e : eMsg);
            log.error(msg, e);
            return null;
        }
    }

    @Override
    public void close() {
    }
}
