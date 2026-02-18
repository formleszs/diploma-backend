package com.studysync.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "project_files")
@Getter
@Setter
public class ProjectFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String originalName;

    private String storedName;

    private String contentType;

    private long size;

    @ManyToOne(fetch = FetchType.LAZY)
    private Project project;
}
