package ru.akonyaev.digitalworker;

import com.espertech.esper.common.client.configuration.Configuration;
import com.espertech.esper.common.internal.util.ExecutionPathDebugLog;
import com.espertech.esper.compiler.client.CompilerArguments;
import com.espertech.esper.compiler.client.EPCompilerProvider;
import com.espertech.esper.runtime.client.EPDeployment;
import com.espertech.esper.runtime.client.EPRuntime;
import com.espertech.esper.runtime.client.EPRuntimeProvider;
import com.espertech.esperio.kafka.EsperIOKafkaConfig;
import com.espertech.esperio.kafka.EsperIOKafkaInputAdapterPlugin;
import com.espertech.esperio.kafka.EsperIOKafkaInputProcessorDefault;
import com.espertech.esperio.kafka.EsperIOKafkaInputSubscriberByTopicList;
import com.espertech.esperio.kafka.EsperIOKafkaInputTimestampExtractorConsumerRecord;
import com.espertech.esperio.kafka.EsperIOKafkaOutputAdapterPlugin;
import com.espertech.esperio.kafka.EsperIOKafkaOutputFlowControllerByAnnotatedStmt;
import com.espertech.esperio.kafka.KafkaOutputDefault;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import ru.akonyaev.digitalworker.events.DeviceStatusEvent;
import ru.akonyaev.digitalworker.events.PersonsInZoneEvent;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Properties;

@Slf4j
public class EsperRunner {

    private final EPRuntime runtime;

    public EsperRunner() {
        this.runtime = setupRuntime();
    }

    private EPRuntime setupRuntime() {
        var config = getConfig();
        var runtime = EPRuntimeProvider.getDefaultRuntime(config);

        ExecutionPathDebugLog.setDebugEnabled(true);
        ExecutionPathDebugLog.setTimerDebugEnabled(true);

        // epl from file
        deployEplResource(
                "unattended-device.epl", getClass().getClassLoader(),
                runtime, config);

        // epl as a text: input events audit example
        var auditDeployment = deployEplText(
                "@Name('Log device status event')\n" +
                        "select logData(e)\n" +
                        "from DeviceStatusEvent e;\n" +
                        "@Name('Log persons in zone event')\n" +
                        "select logData(e)\n" +
                        "from PersonsInZoneEvent e;",
                runtime, config);

        // the above statement is just "select" and Esper will not generate output
        // because it's not being consumed by anyone. Therefore, the logData() method will not be called either.
        // But we can force it to call it by adding a select output listener:
        Arrays.stream(auditDeployment.getStatements())
                .forEach(stmt -> stmt.addListener(
                        (newEvents, oldEvents, statement, runtm) -> log.debug("On stmt: {}", statement.getName())));

        return runtime;
    }

    public void destroy() {
        this.runtime.destroy();
    }

    private static Configuration getConfig() {
        Configuration config = new Configuration();

        // Register event types.
        // In EPL they will be accessible by name MyEvent.class.getSimpleName()
        config.getCommon().addEventType(PersonsInZoneEvent.class);
        config.getCommon().addEventType(DeviceStatusEvent.class);

        // Register plugin to consume input events from Kafka topics
        var inputTopics = String.join(",", TopicEventConfig.getInputTopics());
        config.getRuntime().addPluginLoader(
                EsperIOKafkaInputAdapterPlugin.class.getSimpleName(),
                EsperIOKafkaInputAdapterPlugin.class.getName(),
                new Properties() {{
                    // Kafka consumer parameters
                    put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
                    put(ConsumerConfig.GROUP_ID_CONFIG, "esper-demo");
                    put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                    // so that the names of event classes can be used in EPL rules,
                    // in addition to adding their types via addEventType,
                    // we also need to deserialize them correctly
                    put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, EventDeserializer.class.getName());
                    // list input event's topics
                    put(EsperIOKafkaConfig.TOPICS_CONFIG, inputTopics);
                    put(EsperIOKafkaConfig.INPUT_SUBSCRIBER_CONFIG,
                            EsperIOKafkaInputSubscriberByTopicList.class.getName());
                    put(EsperIOKafkaConfig.INPUT_PROCESSOR_CONFIG,
                            EsperIOKafkaInputProcessorDefault.class.getName());
                    put(EsperIOKafkaConfig.INPUT_TIMESTAMPEXTRACTOR_CONFIG,
                            EsperIOKafkaInputTimestampExtractorConsumerRecord.class.getName());
                }});

        // Register plugin to publish output events to Kafka topics.
        var outputTopics = String.join(",", TopicEventConfig.getOutputTopics());
        config.getRuntime().addPluginLoader(
                EsperIOKafkaOutputAdapterPlugin.class.getSimpleName(),
                EsperIOKafkaOutputAdapterPlugin.class.getName(),
                new Properties() {{
                    put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
                    put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    put(EsperIOKafkaConfig.TOPICS_CONFIG, outputTopics);
                    put(EsperIOKafkaConfig.OUTPUT_FLOWCONTROLLER_CONFIG,
                            EsperIOKafkaOutputFlowControllerByAnnotatedStmt.class.getName());
                }});

        // Register an annotation that indicates that the result of the expression
        // should be published in the Kafka output topics (the topics are set in TOPICS_CONFIG).
        config.getCommon().addImport(KafkaOutputDefault.class);

        // Register functions that can be used inside EPL expressions.
        // Functions must be public static methods.
        registerSingleRowFunctions(PluginFunctions.class, config);

        return config;
    }

    private static void registerSingleRowFunctions(Class<?> functionsHolderClass,
                                                   Configuration config) {
        String className = functionsHolderClass.getName();
        Arrays.stream(functionsHolderClass.getMethods())
                .filter(method -> {
                    int modifiers = method.getModifiers();
                    return Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers);
                })
                .map(Method::getName)
                .forEach(name -> config.getCompiler().addPlugInSingleRowFunction(name, className, name));
    }

    private static EPDeployment deployEplResource(String eplResourceName,
                                                  ClassLoader classLoader,
                                                  EPRuntime runtime,
                                                  Configuration config) {
        try (var inputStream = classLoader.getResourceAsStream(eplResourceName)) {
            var compiler = EPCompilerProvider.getCompiler();
            var moduleUri = FilenameUtils.getName(eplResourceName);
            var module = compiler.readModule(inputStream, moduleUri);
            var args = new CompilerArguments(config);
            args.getPath().add(runtime.getRuntimePath());
            var compiled = compiler.compile(module, args);
            return runtime.getDeploymentService().deploy(compiled);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read EPL: " + eplResourceName, e);
        } catch (Exception e) {
            throw new RuntimeException("Error compiling and deploying EPL resource: " + eplResourceName, e);
        }
    }

    private static EPDeployment deployEplText(String eplText, EPRuntime runtime, Configuration config) {
        try {
            var args = new CompilerArguments(config);
            args.getPath().add(runtime.getRuntimePath());
            var compiled = EPCompilerProvider.getCompiler().compile(eplText, args);
            return runtime.getDeploymentService().deploy(compiled);
        } catch (Exception e) {
            throw new RuntimeException("Error compiling and deploying EPL text", e);
        }
    }
}
