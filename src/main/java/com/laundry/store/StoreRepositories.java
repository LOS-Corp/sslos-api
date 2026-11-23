package com.laundry.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Repository của module cửa hàng (mỗi interface công khai, Spring Data quét được vì là top-level trong package). */
@Repository
interface StoreSettingsRepository extends JpaRepository<StoreSettings, Integer> {
}

@Repository
interface StoreHourRepository extends JpaRepository<StoreHour, UUID> {

    List<StoreHour> findByKindOrderByDayOfWeekAscOpensAtAsc(StoreHour.Kind kind);

    void deleteByKind(StoreHour.Kind kind);
}

@Repository
interface StoreClosureRepository extends JpaRepository<StoreClosure, UUID> {

    @Query("select c from StoreClosure c where c.endsAt > :from order by c.startsAt")
    List<StoreClosure> upcoming(Instant from);

    @Query("select c from StoreClosure c where c.startsAt < :to and c.endsAt > :from")
    List<StoreClosure> overlapping(Instant from, Instant to);
}
