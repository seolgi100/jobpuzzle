package com.example.jobpuzzle.global.init;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 시더가 한 번 넣은 항목의 키. 관리자가 고치거나 지운 초기 데이터를 다음 기동 때 되살리지 않기 위해 둔다. */
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "seed_history")
public class SeedHistory {

    @Id
    @Column(name = "seed_key", length = 64)
    private String seedKey;

    public SeedHistory(String seedKey) {
        this.seedKey = seedKey;
    }
}
