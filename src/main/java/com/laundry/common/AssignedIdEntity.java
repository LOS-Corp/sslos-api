package com.laundry.common;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Entity tự gán id (UUID sinh sẵn để dùng trước khi lưu: mã QR, commandId...).
 * Không có lớp này thì {@code repository.save()} thấy id khác null, coi là entity cũ và gọi {@code merge()}, trả về
 * một bản sao: mọi thay đổi sau đó trên đối tượng gốc bị mất. Ở đây entity mới luôn được {@code persist()}.
 */
@MappedSuperclass
public abstract class AssignedIdEntity<ID> implements Persistable<ID> {

    @Transient
    private boolean fresh = true;

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        fresh = false;
    }
}
