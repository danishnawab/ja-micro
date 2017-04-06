package com.sixt.service.framework.kafka.messaging;

import com.google.common.collect.Lists;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Consumer {
    private static final Logger logger = LoggerFactory.getLogger(Consumer.class);

    private final Topic topic;
    private final String consumerGroupId;
    private final KafkaConsumer<String, byte[]> kafka;
    private final AssignedPartitions partitions;
    private final ExecutorService consumerLoopExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isStopped = new AtomicBoolean(false);

    // TODO Regular task to commit all offsets to not loose the offset after inactivity for e.g. a day
    // -> to refresh the retention on the internal committed offset topic

    // Build by ConsumerFactory
    Consumer(Topic topic, String consumerGroupId, String servers, PartitionProcessorFactory processorFactory) {
        this.topic = topic;
        this.consumerGroupId = consumerGroupId;

        // Kafka consumer set up
        Properties props = new Properties();
        props.put("bootstrap.servers", servers);
        props.put("group.id", consumerGroupId);
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", ByteArrayDeserializer.class.getName());
        props.put("heartbeat.interval.ms", "10000");
        props.put("session.timeout.ms", "20000");
        props.put("enable.auto.commit", "false");
        props.put("auto.offset.reset", "earliest");

        kafka = new KafkaConsumer<>(props);

        partitions = new AssignedPartitions(processorFactory);

        // start it
        consumerLoopExecutor.execute(new ConsumerLoop());
    }


    public void shutdown() {
        isStopped.set(true);

        try {
            consumerLoopExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        Set<TopicPartition> allPartitions = partitions.allPartitions();
        partitions.stopProcessing(allPartitions);
        partitions.waitForHandlersToComplete(allPartitions, 500);
        kafka.commitSync(partitions.offsetsToBeCommitted());

        kafka.close();
    }


    class ConsumerLoop implements Runnable {
        @Override

        public void run() {
            try {
                kafka.subscribe(Lists.newArrayList(topic.toString()), new PartitionAssignmentChange());
                logger.info("Consumer in group {} subscribed to topic {}", consumerGroupId, topic.toString());
            } catch (Exception unexpected){
                logger.error("Dead consumer in group {}: Cannot subscribe to topic {}", consumerGroupId, topic.toString(), unexpected);
                return;
            }

            while (!isStopped.get()) {
                // Note that poll() may also execute the ConsumerRebalanceListener callbacks and may take substantially more time to return.
                ConsumerRecords<String, byte[]> records = kafka.poll(300);

                partitions.enqueue(records);
                kafka.commitSync(partitions.offsetsToBeCommitted());

                kafka.pause(partitions.partitionsToBePaused());
                kafka.resume(partitions.partitionsToBeResumed());
            }
        }
    }


    class PartitionAssignmentChange implements ConsumerRebalanceListener {
        // From the documentation:
        // 1. This callback will execute in the user thread as part of the {@link Consumer#poll(long) poll(long)} call whenever partition assignment changes.
        // 2. It is guaranteed that all consumer processes will invoke {@link #onPartitionsRevoked(Collection) onPartitionsRevoked} prior to
        //    any process invoking {@link #onPartitionsAssigned(Collection) onPartitionsAssigned}.

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> revokedPartitions) {
            partitions.stopProcessing(revokedPartitions);
            partitions.waitForHandlersToComplete(revokedPartitions, 500);

            kafka.commitSync(partitions.offsetsToBeCommitted());

            partitions.removePartitions(revokedPartitions);
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> assignedPartitions) {
            partitions.assignNewPartitions(assignedPartitions);
        }
    }

}
