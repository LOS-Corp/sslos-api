package com.laundry.booking.controller;

import com.laundry.booking.dto.MachineProgramDtos.MachineProgramDto;
import com.laundry.booking.dto.MachineProgramDtos.MachineProgramRequest;
import com.laundry.booking.dto.MachineProgramDtos.ProgramRunMode;
import com.laundry.booking.service.MachineProgramService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Wash / dry program catalogue, shared by all branches. */
@RestController
@RequestMapping("/api/admin/booking-config/programs")
@RequiredArgsConstructor
public class MachineProgramAdminController {
    private final MachineProgramService programService;

    @GetMapping
    public List<MachineProgramDto> list() {
        return programService.listAll();
    }

    @PostMapping
    public MachineProgramDto create(@Valid @RequestBody MachineProgramRequest request) {
        return programService.create(request);
    }

    @GetMapping("/run-mode")
    public ProgramRunMode runMode() {
        return new ProgramRunMode(programService.timeDivisor());
    }

    @PutMapping("/run-mode")
    public ProgramRunMode setRunMode(@Valid @RequestBody ProgramRunMode request) {
        return new ProgramRunMode(programService.setTimeDivisor(request.timeDivisor()));
    }

    @PutMapping("/{code}")
    public MachineProgramDto update(@PathVariable String code, @Valid @RequestBody MachineProgramRequest request) {
        return programService.update(code, request);
    }
}
