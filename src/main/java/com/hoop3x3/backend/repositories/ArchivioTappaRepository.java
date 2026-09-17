package com.hoop3x3.backend.repositories;

import com.hoop3x3.backend.entities.ArchivioTappa;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ArchivioTappaRepository extends JpaRepository<ArchivioTappa, UUID> {
    List<ArchivioTappa> findAllByOrderByPubblicatoIlDesc();
}
