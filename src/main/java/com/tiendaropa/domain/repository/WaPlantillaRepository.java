package com.tiendaropa.domain.repository;

import com.tiendaropa.domain.model.WaPlantilla;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WaPlantillaRepository extends JpaRepository<WaPlantilla, UUID> {

    List<WaPlantilla> findByActivaTrueOrderByTitulo();

    List<WaPlantilla> findAllByOrderByTitulo();
}
