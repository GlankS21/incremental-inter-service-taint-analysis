package com.shop.product.service.impl;

import com.shop.common.exception.BadRequestException;
import com.shop.common.exception.ResourceNotFoundException;
import com.shop.product.config.CatalogProperties;
import com.shop.product.dto.response.UploadedImageResponse;
import com.shop.product.service.ImageStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalImageStorageServiceTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};
    static final byte[] GIF = "GIF89a\u0001\u0000\u0001\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] WEBP = "RIFF$\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);

    @TempDir
    Path tempDir;

    private Path storageDir;
    private LocalImageStorageService service;

    @BeforeEach
    void setUp() {
        storageDir = tempDir.resolve("images");
        service = new LocalImageStorageService(
                new CatalogProperties("RUB", storageDir.toString(), DataSize.ofKilobytes(1)));
    }

    @Test
    void createsTheDirectoryAtStartup() {
        assertThat(storageDir).isDirectory();
    }

    @Test
    void storesByDetectedTypeUnderRandomName() throws Exception {
        // Declared type and name lie: the content decides
        UploadedImageResponse png = service.store(file("photo.jpg", MediaType.IMAGE_JPEG_VALUE, PNG));

        assertThat(png.url()).matches("^/api/products/images/[0-9a-f-]{36}\\.png$");
        assertThat(png.contentType()).isEqualTo("image/png");
        assertThat(png.size()).isEqualTo(PNG.length);
        Path stored = storageDir.resolve(png.url().substring(png.url().lastIndexOf('/') + 1));
        assertThat(Files.readAllBytes(stored)).isEqualTo(PNG);

        assertThat(service.store(file("a", "application/octet-stream", JPEG)).url()).endsWith(".jpg");
        assertThat(service.store(file("a", "application/octet-stream", GIF)).url()).endsWith(".gif");
        assertThat(service.store(file("a", "application/octet-stream", WEBP)).url()).endsWith(".webp");
    }

    @Test
    void rejectsTextRenamedToPng() {
        byte[] text = "just some text, not an image".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.store(file("fake.png", MediaType.IMAGE_PNG_VALUE, text)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("image.error.unsupported-type");
        assertThat(storageDir.toFile().list()).isEmpty();
    }

    @Test
    void rejectsEmptyAndMissingFiles() {
        assertThatThrownBy(() -> service.store(file("empty.png", MediaType.IMAGE_PNG_VALUE, new byte[0])))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("image.error.empty");
        assertThatThrownBy(() -> service.store(null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("image.error.empty");
    }

    @Test
    void rejectsFilesAboveTheLimit() {
        byte[] large = Arrays.copyOf(PNG, 1025);

        assertThatThrownBy(() -> service.store(file("large.png", MediaType.IMAGE_PNG_VALUE, large)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("image.error.too-large")
                .satisfies(ex -> assertThat(((BadRequestException) ex).getMessageArgs()).containsExactly("1 КБ"));
    }

    @Test
    void loadsStoredImageWithItsType() {
        UploadedImageResponse uploaded = service.store(file("x.gif", MediaType.IMAGE_GIF_VALUE, GIF));
        String filename = uploaded.url().substring(uploaded.url().lastIndexOf('/') + 1);

        ImageStorageService.StoredImage image = service.load(filename);

        assertThat(image.contentType()).isEqualTo(MediaType.IMAGE_GIF);
        assertThat(image.size()).isEqualTo(GIF.length);
        assertThat(image.resource().exists()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../secret.png",
            "..%2Fsecret.png",
            "..\\secret.png",
            "/etc/passwd",
            "00000000-0000-0000-0000-000000000000.exe",
            "00000000-0000-0000-0000-000000000000.PNG",
            "00000000-0000-0000-0000-000000000000.png/../x",
            "image.png",
            ""
    })
    void rejectsNamesNotGeneratedByTheStorage(String filename) throws Exception {
        // A file outside the storage directory that a traversal would reach
        Files.write(tempDir.resolve("secret.png"), PNG);

        assertThatThrownBy(() -> service.load(filename))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("image.error.not-found");
    }

    @Test
    void wellFormedButMissingNameIsNotFound() {
        assertThatThrownBy(() -> service.load("00000000-0000-0000-0000-000000000000.png"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }
}
