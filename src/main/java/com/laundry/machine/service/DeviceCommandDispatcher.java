package com.laundry.machine.service;

import com.laundry.machine.entity.DeviceCommandStatus;
import com.laundry.machine.repository.DeviceCommandRepository;
import com.laundry.mqtt.service.MqttPublisher;
import lombok.RequiredArgsConstructor;
import org.eclipse.paho.client.mqttv3.MqttException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;

/**
 * Publishes a stored command to its device after the issuing transaction committed (ADR-08), then
 * records SENT or FAILED in its own transaction. A command whose ACK never arrives is turned into
 * TIMEOUT by the command-timeout job (BR-31).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceCommandDispatcher {

    private final MqttPublisher mqttPublisher;
    private final DeviceCommandRepository commands;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(DeviceCommandRequested event) {
        commands.findById(event.commandId()).ifPresent(command -> {
            DeviceCommandRequested.Payload payload = event.payload();
            String topic = mqttPublisher.buildWasherCommandTopic(payload.branchId(), payload.machineCode());
            try {
                mqttPublisher.publish(topic, payload.json());
                command.setStatus(DeviceCommandStatus.SENT);
                command.setSentAt(OffsetDateTime.now());
                log.info("[{}] Command {} published to {}", payload.machineCode(), command.getCommandId(), topic);
            } catch (MqttException | RuntimeException e) {
                command.setStatus(DeviceCommandStatus.FAILED);
                command.setAckReason("Publish failed: " + e.getMessage());
                log.error("[{}] Failed to publish command {}", payload.machineCode(), command.getCommandId(), e);
            }
            commands.save(command);
        });
    }
}
