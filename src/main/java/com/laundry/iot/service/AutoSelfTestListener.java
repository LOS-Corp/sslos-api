package com.laundry.iot.service;

import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.machine.entity.MachineDeploymentStatus;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.MachineCameOnline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts the commissioning self-test on its own the first time a freshly
 * provisioned board reaches MQTT, so the technician only has to press START
 * when the board beeps. Later runs (re-test, after maintenance) stay manual
 * via {@code POST /api/admin/machines/{code}/self-test}.
 */
@Slf4j
@Component
public class AutoSelfTestListener {

    private final MachineRepository machineRepository;
    private final IotDeviceRepository deviceRepository;
    private final CommissioningService commissioningService;
    private final TransactionTemplate newTransaction;

    public AutoSelfTestListener(MachineRepository machineRepository, IotDeviceRepository deviceRepository,
                                CommissioningService commissioningService, PlatformTransactionManager txManager) {
        this.machineRepository = machineRepository;
        this.deviceRepository = deviceRepository;
        this.commissioningService = commissioningService;
        this.newTransaction = new TransactionTemplate(txManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs after the heartbeat committed. The self-test runs in its own
     * transaction so a refusal (device busy, heartbeat raced the offline
     * detector) rolls back only itself and is logged, not rethrown.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMachineCameOnline(MachineCameOnline event) {
        try {
            boolean started = Boolean.TRUE.equals(newTransaction.execute(status -> {
                boolean firstContact = machineRepository.findById(event.machineId())
                        .filter(m -> m.getDeploymentStatus() == MachineDeploymentStatus.PROVISIONED
                                && m.getDeviceId() != null)
                        .flatMap(m -> deviceRepository.findByDeviceId(m.getDeviceId()))
                        .map(d -> d.getLastSelfTestCommandId() == null)
                        .orElse(false);
                if (!firstContact) return false;
                commissioningService.startSelfTest(event.machineCode(), null);
                return true;
            }));
            if (started) log.info("[IOT] Auto self-test started for {}", event.machineCode());
        } catch (RuntimeException e) {
            // The admin can still start it from the deployment panel.
            log.warn("[IOT] Auto self-test for {} not started: {}", event.machineCode(), e.getMessage());
        }
    }
}
