package com.company.filepreview.sql;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SqlDataSourceRepository extends JpaRepository<SqlDataSource, Long> {
    Optional<SqlDataSource> findByName(String name);

    boolean existsByName(String name);
}
