package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.WaPlantillaMeta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WaPlantillaMetaRepository extends JpaRepository<WaPlantillaMeta, UUID> {

    List<WaPlantillaMeta> findByActivaTrueOrderByNombre();

    List<WaPlantillaMeta> findAllByOrderByNombre();
}
