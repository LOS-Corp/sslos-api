package com.laundry.wallet.controller;

import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Roles;
import com.laundry.wallet.dto.WalletDtos.WalletView;
import com.laundry.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "Customer credit wallet (UC-13)")
public class WalletController {

    private final WalletService walletService;

    @GetMapping("/me")
    @PreAuthorize(Roles.CUSTOMER)
    @Operation(summary = "Balance and ledger of the caller's wallet")
    public WalletView myWallet(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size,
                               CurrentUser customer) {
        return walletService.view(customer.id(), page, size);
    }
}
