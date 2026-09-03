package com.tiendaropa.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RestController
@RequestMapping("/api/media")
@Slf4j
public class WaMediaController {

    private final String accessToken;
    private final String mediaDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public WaMediaController(
            @Value("${whatsapp.access-token}") String accessToken,
            @Value("${whatsapp.media-dir:./media}") String mediaDir) {
        this.accessToken = accessToken;
        this.mediaDir = mediaDir;
    }

    @GetMapping("/local/{subdir}/{filename:.+}")
    public ResponseEntity<Resource> descargarLocal(
            @PathVariable String subdir,
            @PathVariable String filename) {
        try {
            Path filePath = Paths.get(mediaDir, subdir, filename).normalize();
            if (!Files.exists(filePath) || !filePath.startsWith(Paths.get(mediaDir).normalize())) {
                return ResponseEntity.notFound().build();
            }
            var resource = new FileSystemResource(filePath);
            var contentType = Files.probeContentType(filePath);
            if (contentType == null) contentType = "application/octet-stream";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .cacheControl(CacheControl.maxAge(86400, java.util.concurrent.TimeUnit.SECONDS).cachePublic())
                    .body(resource);
        } catch (Exception e) {
            log.error("Error sirviendo archivo local {}/{}: {}", subdir, filename, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{mediaId}")
    public ResponseEntity<byte[]> descargar(@PathVariable String mediaId) {
        try {
            var metaUrl = URI.create("https://graph.facebook.com/v19.0/" + mediaId + "?fields=url,mime_type");
            var conn = (HttpURLConnection) metaUrl.toURL().openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            var metaStatus = conn.getResponseCode();
            if (metaStatus >= 400) {
                // getInputStream() lanza IOException en respuestas de error y se pierde el
                // cuerpo real que manda Meta (por ejemplo "Error validating access token" con
                // code=190 si el token expiró). Lo leemos de getErrorStream() para poder loguear
                // y devolver el motivo real en vez de solo "400 Bad Gateway" sin contexto.
                var errorBody = leerCuerpo(conn.getErrorStream());
                log.error("[MEDIA] Meta devolvió {} al pedir metadata de {}: {}", metaStatus, mediaId, errorBody);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(("Meta respondió " + metaStatus + " al consultar el media: " + errorBody)
                                .getBytes(StandardCharsets.UTF_8));
            }

            var meta = mapper.readValue(conn.getInputStream(), Map.class);
            var urlStr = (String) meta.get("url");
            var mime = meta.containsKey("mime_type") ? (String) meta.get("mime_type") : "application/octet-stream";

            log.info("Streaming media {} -> {} ({})", mediaId, mime, urlStr);

            var dlConn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
            dlConn.setRequestProperty("Authorization", "Bearer " + accessToken);
            dlConn.setConnectTimeout(15000);
            dlConn.setReadTimeout(30000);

            var dlStatus = dlConn.getResponseCode();
            if (dlStatus >= 400) {
                var errorBody = leerCuerpo(dlConn.getErrorStream());
                log.error("[MEDIA] Meta devolvió {} al descargar el binario de {}: {}", dlStatus, mediaId, errorBody);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(("Meta respondió " + dlStatus + " al descargar el archivo: " + errorBody)
                                .getBytes(StandardCharsets.UTF_8));
            }

            var in = dlConn.getInputStream();
            var buf = new ByteArrayOutputStream();
            var tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            in.close();

            var headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(mime));
            headers.setCacheControl(CacheControl.maxAge(3600, java.util.concurrent.TimeUnit.SECONDS).cachePrivate());
            return new ResponseEntity<>(buf.toByteArray(), headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error descargando media {}: {}", mediaId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(("Error: " + e.getMessage()).getBytes());
        }
    }

    private String leerCuerpo(InputStream in) {
        if (in == null) return "(sin cuerpo de error)";
        try {
            var buf = new ByteArrayOutputStream();
            var tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            in.close();
            return buf.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(no se pudo leer el cuerpo del error: " + e.getMessage() + ")";
        }
    }
}
