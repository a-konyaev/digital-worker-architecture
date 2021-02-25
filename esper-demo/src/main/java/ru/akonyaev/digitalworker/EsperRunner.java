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

        // epl из файла
        deployEplResource(
                "unattended-device.epl", getClass().getClassLoader(),
                runtime, config);

        // epl в виде строки - пример того, как можно аудировать поступающие на вход события
        var auditDeployment = deployEplText(
                "@Name('Log device status event')\n" +
                        "select logData(e)\n" +
                        "from DeviceStatusEvent e;\n" +
                        "@Name('Log persons in zone event')\n" +
                        "select logData(e)\n" +
                        "from PersonsInZoneEvent e;",
                runtime, config);

        // т.к. выражения выше - просто селекты, то Esper не будет их выполнять, вернее, не будет
        // формировать результат селекта, потому что его никто не потребляет,
        // и результате, не будет вызываться метод logData().
        // Чтобы все таки метод logData() вызвался,
        // создадим "потредителя" результата селекта путем добавления Listener-а!
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

        // Регистрируем типы событий
        // в EPL они будут доступны по имени MyEvent.class.getSimpleName()
        config.getCommon().addEventType(PersonsInZoneEvent.class);
        config.getCommon().addEventType(DeviceStatusEvent.class);

        // Регистрируем плагины для чтения топиков Kafka
        var inputTopics = String.join(",", TopicEventConfig.getInputTopics());
        config.getRuntime().addPluginLoader(
                EsperIOKafkaInputAdapterPlugin.class.getSimpleName(),
                EsperIOKafkaInputAdapterPlugin.class.getName(),
                new Properties() {{
                    // параметры Kafka-consumer-a
                    put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
                    put(ConsumerConfig.GROUP_ID_CONFIG, "esper-demo");
                    put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                    // чтобы в EPL-правилах можно было использовать имена классов-событий,
                    // кроме добавление их типов через addEventType, нужно еще правильно их десериализовывать,
                    // чтобы на выходе из Kafka мы получали типизированное значение события
                    put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, EventDeserializer.class.getName());
                    // перечисляем топики, из которых будем потреблять "входные" для правил события
                    // и используем готовые реализации подписчика и процессора событий
                    put(EsperIOKafkaConfig.TOPICS_CONFIG, inputTopics);
                    put(EsperIOKafkaConfig.INPUT_SUBSCRIBER_CONFIG,
                            EsperIOKafkaInputSubscriberByTopicList.class.getName());
                    put(EsperIOKafkaConfig.INPUT_PROCESSOR_CONFIG,
                            EsperIOKafkaInputProcessorDefault.class.getName());
                    put(EsperIOKafkaConfig.INPUT_TIMESTAMPEXTRACTOR_CONFIG,
                            EsperIOKafkaInputTimestampExtractorConsumerRecord.class.getName());
                }});

        // Регистрируем плагины для записи в топики Kafka
        var outputTopics = String.join(",", TopicEventConfig.getOutputTopics());
        config.getRuntime().addPluginLoader(
                EsperIOKafkaOutputAdapterPlugin.class.getSimpleName(),
                EsperIOKafkaOutputAdapterPlugin.class.getName(),
                new Properties() {{
                    // параметры Kafka-producer-a
                    put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
                    put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    // перечисляем топики, в которые будем публиковать "выходные" события
                    put(EsperIOKafkaConfig.TOPICS_CONFIG, outputTopics);
                    put(EsperIOKafkaConfig.OUTPUT_FLOWCONTROLLER_CONFIG,
                            EsperIOKafkaOutputFlowControllerByAnnotatedStmt.class.getName());
                }});
        // Регистрируем аннотацию, которая указывает, что результат выражения должен быть
        // опубликован в выходные топики Kafka (топики задаем в TOPICS_CONFIG)
        config.getCommon().addImport(KafkaOutputDefault.class);

        // Регистрируем функции, которые можно будет использовать внутри epl-выражений
        // Функциями должны быть public static методы
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
