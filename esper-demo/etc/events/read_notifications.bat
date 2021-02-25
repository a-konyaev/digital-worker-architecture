docker exec kafka kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic notification --property print.key=true --property print.value=true
