kafka-console-producer.sh --broker-list kafka:9092 --topic $1 --property parse.key=true --property key.separator=: < /event.txt