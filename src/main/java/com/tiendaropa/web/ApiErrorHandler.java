package com.tiendaropa.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Las validaciones de los servicios (ej. "Primero registra el valor del pago") se lanzan como
 * IllegalArgumentException / IllegalStateException. Sin esto Spring las devolvía como un 500
 * sin mensaje, y el panel no podía decirle al operador qué salió mal. Ahora llegan como
 * { "message": "..." } con un código 4xx, que el frontend muestra tal cual.
 */
@RestControllerAdvice
@Slf4j
public class ApiErrorHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> argumentoInvalido(IllegalArgumentException e) {
        log.info("[API] 400: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", mensaje(e)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> estadoInvalido(IllegalStateException e) {
        log.info("[API] 409: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", mensaje(e)));
    }

    private static String mensaje(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "Solicitud no válida" : e.getMessage();
    }
}
