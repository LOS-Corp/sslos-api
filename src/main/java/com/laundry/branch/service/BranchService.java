package com.laundry.branch.service;

import com.laundry.branch.dto.BranchDto;
import com.laundry.branch.dto.CreateBranchRequest;
import com.laundry.branch.dto.UpdateBranchRequest;
import com.laundry.branch.entity.Branch;
import com.laundry.branch.entity.BranchStatus;
import com.laundry.branch.exception.BranchNotFoundException;
import com.laundry.branch.repository.BranchRepository;
import com.laundry.shared.exception.DuplicateResourceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BranchService {

    private final BranchRepository branchRepository;

    @Transactional
    public BranchDto createBranch(CreateBranchRequest request) {
        String trimmedName = request.getName().trim();
        if (branchRepository.existsByNameIgnoreCase(trimmedName)) {
            throw new DuplicateResourceException("Branch name already exists: " + trimmedName);
        }

        Branch branch = Branch.builder()
                .name(trimmedName)
                .address(request.getAddress() != null ? request.getAddress().trim() : null)
                .phone(request.getPhone() != null ? request.getPhone().trim() : null)
                .mapEmbedUrl(normalizeMapEmbedUrl(request.getMapEmbedUrl()))
                .status(BranchStatus.ACTIVE)
                .build();

        branch = branchRepository.save(branch);
        log.info("Created new branch: {} ({})", branch.getName(), branch.getId());
        return toDto(branch);
    }

    @Transactional
    public BranchDto updateBranch(UUID id, UpdateBranchRequest request) {
        Branch branch = branchRepository.findById(id)
                .orElseThrow(() -> BranchNotFoundException.byId(id));

        boolean changed = false;

        if (request.getName() != null && !request.getName().isBlank()) {
            String newName = request.getName().trim();
            if (!newName.equalsIgnoreCase(branch.getName())) {
                if (branchRepository.existsByNameIgnoreCaseAndIdNot(newName, id)) {
                    throw new DuplicateResourceException("Branch name already exists: " + newName);
                }
                branch.setName(newName);
                changed = true;
            }
        }

        if (request.getAddress() != null) {
            String newAddress = request.getAddress().trim();
            if (!newAddress.equals(branch.getAddress())) {
                branch.setAddress(newAddress);
                changed = true;
            }
        }

        if (request.getPhone() != null) {
            String newPhone = request.getPhone().trim();
            if (!newPhone.equals(branch.getPhone())) {
                branch.setPhone(newPhone);
                changed = true;
            }
        }

        if (request.getMapEmbedUrl() != null) {
            String newUrl = normalizeMapEmbedUrl(request.getMapEmbedUrl());
            if (!newUrl.equals(branch.getMapEmbedUrl())) {
                branch.setMapEmbedUrl(newUrl);
                changed = true;
            }
        }

        if (request.getStatus() != null && request.getStatus() != branch.getStatus()) {
            branch.setStatus(request.getStatus());
            changed = true;
        }

        if (changed) {
            branch = branchRepository.save(branch);
            log.info("Updated branch: {} ({})", branch.getName(), branch.getId());
        }

        return toDto(branch);
    }

    @Transactional
    public void deleteBranch(UUID id) {
        Branch branch = branchRepository.findById(id)
                .orElseThrow(() -> BranchNotFoundException.byId(id));

        branchRepository.delete(branch);
        log.info("Deleted branch: {} ({})", branch.getName(), id);
    }

    @Transactional(readOnly = true)
    public BranchDto getBranchById(UUID id) {
        return branchRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> BranchNotFoundException.byId(id));
    }

    @Transactional(readOnly = true)
    public Page<Branch> searchBranches(String q, BranchStatus status, Pageable pageable) {
        String cleanQ = (q != null && !q.isBlank()) ? q.trim() : null;
        return branchRepository.searchBranches(cleanQ, status, pageable);
    }

    @Transactional(readOnly = true)
    public List<BranchDto> getActiveBranches() {
        return branchRepository.findByStatusOrderByNameAsc(BranchStatus.ACTIVE)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public BranchDto toDto(Branch branch) {
        if (branch == null) return null;
        return BranchDto.builder()
                .id(branch.getId())
                .name(branch.getName())
                .address(branch.getAddress())
                .phone(branch.getPhone())
                .mapEmbedUrl(branch.getMapEmbedUrl())
                .status(branch.getStatus())
                .createdAt(branch.getCreatedAt())
                .updatedAt(branch.getUpdatedAt())
                .build();
    }

    /**
     * Trim và trả về null nếu URL rỗng sau khi trim.
     * Cho phép admin "xóa" URL bằng cách gửi string rỗng.
     */
    private String normalizeMapEmbedUrl(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
