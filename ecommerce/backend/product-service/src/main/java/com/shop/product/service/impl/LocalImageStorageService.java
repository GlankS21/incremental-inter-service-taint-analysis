package com.shop.product.service.impl;

import com.shop.common.exception.BadRequestException;
import com.shop.common.exception.ResourceNotFoundException;
import com.shop.product.config.CatalogProperties;
import com.shop.product.dto.response.UploadedImageResponse;
import com.shop.product.service.ImageStorageService;
import com.shop.product.util.ImageType;
import com.shop.product.util.SizeFormatter;
import com.shop.product.util.ValidationPatterns;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores images as {@code <random UUID>.<extension>} in {@code app.catalog.image-storage-dir}.
 * The name never comes from the client, and only names of that exact form are served, which rules
 * out path traversal.
 */
@Slf4j
@Service
public class LocalImageStorageService implements ImageStorageService {

    private static final Pattern FILENAME = Pattern.compile(ValidationPatterns.IMAGE_FILENAME);

    private final Path storageDir;
    private final DataSize maxSize;

    public LocalImageStorageService(CatalogProperties catalogProperties) {
        this.storageDir = Path.of(catalogProperties.imageStorageDir()).toAbsolutePath().normalize();
        this.maxSize = catalogProperties.imageMaxSize();
        try {
            Files.createDirectories(storageDir);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot create the image directory " + storageDir, ex);
        }
        log.info("Product images are stored in {} (at most {} per file)", storageDir, maxSize);
    }

    @Override
    public UploadedImageResponse store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("image.error.empty");
        }
        if (file.getSize() > maxSize.toBytes()) {
            throw new BadRequestException("image.error.too-large", SizeFormatter.format(maxSize));
        }
        ImageType type = ImageType.detect(readHeader(file))
                .orElseThrow(() -> new BadRequestException("image.error.unsupported-type"));

        String filename = UUID.randomUUID() + "." + type.extension();
        Path target = storageDir.resolve(filename);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target);
        } catch (IOException ex) {
            deleteQuietly(target);
            throw new UncheckedIOException("Cannot store the uploaded image " + filename, ex);
        }
        log.info("Stored image {} ({}, {} bytes)", filename, type.contentType(), file.getSize());
        return new UploadedImageResponse(ValidationPatterns.UPLOADED_IMAGE_PATH + filename,
                type.contentType(), file.getSize());
    }

    @Override
    public StoredImage load(String filename) {
        if (filename == null || !FILENAME.matcher(filename).matches()) {
            throw ResourceNotFoundException.of("image.error.not-found");
        }
        Path path = storageDir.resolve(filename).normalize();
        // Defence in depth: the pattern already excludes separators and dots outside the extension
        if (!storageDir.equals(path.getParent()) || !Files.isRegularFile(path)) {
            throw ResourceNotFoundException.of("image.error.not-found");
        }
        ImageType type = ImageType.fromExtension(filename.substring(filename.lastIndexOf('.') + 1))
                .orElseThrow(() -> ResourceNotFoundException.of("image.error.not-found"));
        try {
            return new StoredImage(new FileSystemResource(path), MediaType.parseMediaType(type.contentType()),
                    Files.size(path));
        } catch (IOException ex) {
            throw ResourceNotFoundException.of("image.error.not-found");
        }
    }

    private static byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(ImageType.HEADER_LENGTH);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read the uploaded file", ex);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Could not delete the partial file {}", path, ex);
        }
    }
}
