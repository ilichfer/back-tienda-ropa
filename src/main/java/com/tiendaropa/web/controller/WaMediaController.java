package com.tiendaropa.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
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

            var meta = mapper.readValue(conn.getInputStream(), Map.class);
            var urlStr = (String) meta.get("url");
            var mime = meta.containsKey("mime_type") ? (String) meta.get("mime_type") : "application/octet-stream";

            log.info("Streaming media {} -> {} ({})", mediaId, mime, urlStr);

            var dlConn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
            dlConn.setRequestProperty("Authorization", "Bearer " + accessToken);
            dlConn.setConnectTimeout(15000);
            dlConn.setReadTimeout(30000);

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
}
