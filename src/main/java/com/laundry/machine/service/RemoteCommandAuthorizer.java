package com.laundry.machine.service;

import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStartAuthorization;
import com.laundry.machine.entity.MachineStartAuthorizationStatus;
import com.laundry.machine.repository.MachineStartAuthorizationRepository;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Who may send which command to a machine (FR-ACT-06, FR-ACT-11, BR-25, SC-20, SC-45).
 * <ul>
 *   <li>Customer: STOP / STATUS on the machine's latest session, and only if it is theirs.</li>
 *   <li>Staff, Owner, Admin: any known command; every command except STATUS needs a reason.</li>
 * </ul>
 * Customers start machines through QR check-in, never through this endpoint.
 */
@Component
@RequiredArgsConstructor
public class RemoteCommandAuthorizer {

    /** Remote commands the firmware executes; anything else is refused before it reaches a device. */
    static final Set<String> KNOWN_COMMANDS = Set.of("STOP", "RESET", "STATUS");
    static final Set<String> CUSTOMER_COMMANDS = Set.of("STOP", "STATUS");
    static final String READ_ONLY_COMMAND = "STATUS";

    private static final Set<MachineStartAuthorizationStatus> LIVE_SESSION = EnumSet.of(
            MachineStartAuthorizationStatus.GRANTED,
            MachineStartAuthorizationStatus.START_PENDING,
            MachineStartAuthorizationStatus.CONSUMED);

    private final MachineStartAuthorizationRepository authorizations;

    public void check(CurrentUser actor, Machine machine, String command, String reason) {
        if (!KNOWN_COMMANDS.contains(command)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Unknown command: " + command);
        }
        if (actor.isOperator()) {
            if (!READ_ONLY_COMMAND.equals(command) && (reason == null || reason.isBlank())) {
                throw BusinessException.of(ErrorCode.REASON_REQUIRED, "Please enter a reason for this action");
            }
            return;
        }
        if (actor.has(Role.CUSTOMER) && CUSTOMER_COMMANDS.contains(command) && ownsLatestSession(actor, machine)) {
            return;
        }
        throw BusinessException.forbidden("You do not have permission for this action");
    }

    private boolean ownsLatestSession(CurrentUser customer, Machine machine) {
        return authorizations.findFirstByMachineIdOrderByCreatedAtDesc(machine.getId())
                .filter(auth -> !auth.isTestRun())
                .filter(auth -> LIVE_SESSION.contains(auth.getStatus()))
                .map(MachineStartAuthorization::getUserId)
                .filter(customer.id()::equals)
                .isPresent();
    }
}
