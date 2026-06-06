package kz.courier.courierservice.service;

import jakarta.annotation.PostConstruct;
import kz.courier.courierservice.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Slf4j
@Service
public class DocumentStorageService {

    private final Path root = Paths.get("uploads");

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(root);
            log.info("Initialized document storage directory at: {}", root.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize folder for upload!", e);
        }
    }

    public String store(MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new BusinessException("INVALID_ARGUMENT", "Failed to store empty file.");
            }

            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }

            String storedFilename = UUID.randomUUID().toString() + extension;
            Path destinationFile = this.root.resolve(Paths.get(storedFilename)).normalize().toAbsolutePath();

            if (!destinationFile.getParent().equals(this.root.toAbsolutePath())) {
                throw new BusinessException("SECURITY_ERROR", "Cannot store file outside current directory.");
            }

            Files.copy(file.getInputStream(), destinationFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Stored file locally as: {}", storedFilename);
            return storedFilename;
        } catch (IOException e) {
            log.error("Failed to store file", e);
            throw new BusinessException("INTERNAL_ERROR", "Failed to store file: " + e.getMessage());
        }
    }

    public Resource load(String filename) {
        try {
            Path file = root.resolve(filename);
            Resource resource = new UrlResource(file.toUri());

            if (resource.exists() || resource.isReadable()) {
                return resource;
            } else {
                throw new BusinessException("DOCUMENT_NOT_FOUND", "Could not read the file: " + filename);
            }
        } catch (MalformedURLException e) {
            throw new BusinessException("INTERNAL_ERROR", "Error: " + e.getMessage());
        }
    }
}
