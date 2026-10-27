package com.laundry.wallet.controller;

import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Roles;
import com.laundry.wallet.dto.WalletDtos.WithdrawalDecision;
import com.laundry.wallet.dto.WalletDtos.WithdrawalDto;
import com.laundry.wallet.dto.WalletDtos.WithdrawalRequest;
import com.laundry.wallet.service.WithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** UC-13 wallet withdrawal: customer requests, Owner completes with proof or rejects with a reason. */
@RestController
@RequestMapping("/api/wallet/withdrawals")
@RequiredArgsConstructor
@Tag(name = "Wallet withdrawals", description = "UC-13 withdraw wallet credit to a bank account")
public class WithdrawalController {

    private final WithdrawalService withdrawals;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CUSTOMER)
    @Operation(summary = "Request a withdrawal; the amount is held at once")
    public WithdrawalDto request(@Valid @RequestBody WithdrawalRequest request, CurrentUser customer) {
        return withdrawals.request(customer, request);
    }

    @GetMapping("/me")
    @PreAuthorize(Roles.CUSTOMER)
    @Operation(summary = "The caller's withdrawals")
    public List<WithdrawalDto> mine(CurrentUser customer) {
        return withdrawals.mine(customer.id());
    }

    @GetMapping
    @PreAuthorize(Roles.OWNER)
    @Operation(summary = "Withdrawals waiting for the Owner's transfer")
    public List<WithdrawalDto> processing() {
        return withdrawals.processing();
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize(Roles.OWNER)
    @Operation(summary = "Mark as transferred; a transfer proof is mandatory (BR-15)")
    public WithdrawalDto complete(@PathVariable UUID id, @Valid @RequestBody WithdrawalDecision decision,
                                  CurrentUser owner) {
        return withdrawals.complete(id, decision.proof(), owner);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(Roles.OWNER)
    @Operation(summary = "Reject with a reason; the held amount returns to the wallet")
    public WithdrawalDto reject(@PathVariable UUID id, @Valid @RequestBody WithdrawalDecision decision,
                                CurrentUser owner) {
        return withdrawals.reject(id, decision.reason(), owner);
    }
}
