package com.payflux.transaction_service.kafka;

import com.payflux.transaction_service.entity.Transaction;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.support.SendResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class KafkaEventProducer {

    private static final String TOPIC = "txn-initiated";

    private final KafkaTemplate<String, Transaction> kafkaTemplate;

    @Autowired
    public KafkaEventProducer(KafkaTemplate<String, Transaction> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendTransactionEvent(String key, Transaction transaction){
        System.out.printf("Sending to Kafka -> Topic: "+TOPIC+", Key: "+key+", Message: "+ transaction);

        CompletableFuture<SendResult<String, Transaction>> future = kafkaTemplate.send(TOPIC,key,transaction);

        future.thenAccept(result->{
            RecordMetadata metadata = result.getRecordMetadata();
            System.out.printf("Kafka message sent successfully! Topics: "+metadata.topic()+", Partiton: " + metadata.partition() + ", Offset: " + metadata.offset());
        }).exceptionally(ex->{
            System.err.println("Failed to send Kafka message: "+ ex.getMessage());
            ex.printStackTrace();
            return null;
        });
    }
}
