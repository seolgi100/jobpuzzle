package com.example.jobpuzzle.competency.entity;

import com.example.jobpuzzle.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 직무별 역량 태그 (평가 명세 4-1). 직무는 경력 구분 없이 대분류+중분류 단위. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "job_competency_tag", uniqueConstraints =
        @UniqueConstraint(name = "uk_job_competency_tag", columnNames = {"main_category", "sub_category", "code"}))
public class JobCompetencyTag extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_competency_tag_id")
    private Long jobCompetencyTagId;

    @Column(name = "main_category", nullable = false, length = 50)
    private String mainCategory;

    @Column(name = "sub_category", nullable = false, length = 50)
    private String subCategory;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 30)
    private String name;

    @Column(name = "description", nullable = false, length = 300)
    private String description;

    public JobCompetencyTag(String mainCategory, String subCategory, String code, String name, String description) {
        this.mainCategory = mainCategory;
        this.subCategory = subCategory;
        update(code, name, description);
    }

    public void update(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }
}
