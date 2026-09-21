package com.esun.shop.service;

import com.esun.shop.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-focused tests for {@link ProductImageStorageService}: uploaded bytes must never be able to
 * choose their own path, and only genuine JPEG/PNG/WEBP payloads are accepted.
 */
class ProductImageStorageServiceTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    @TempDir
    Path tempDir;
    private Path root;
    private ProductImageStorageService service;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("products");
        service = new ProductImageStorageService(root.toString());
    }

    @Test
    void storesGenuineImagesUnderServerGeneratedNamesAndReturnsPublicUrls() throws IOException {
        List<ProductImageStorageService.StoredImage> stored = service.store(new MultipartFile[]{
                file("a.png", "image/png", PNG), file("b.JPEG", "image/jpeg", JPEG), file("c.webp", "image/webp", WEBP)});

        assertThat(stored).hasSize(3);
        assertThat(stored).allSatisfy(image -> {
            assertThat(image.url()).startsWith("/uploads/products/");
            assertThat(image.path().getParent()).isEqualTo(root.toAbsolutePath().normalize());
            assertThat(image.path()).exists();
        });
        assertThat(stored.get(0).url()).endsWith(".png");
        assertThat(stored.get(1).url()).endsWith(".jpg");
        assertThat(stored.get(2).url()).endsWith(".webp");
        assertThat(listNames(root)).hasSize(3);
    }

    @Test
    void traversalFilenameNeverInfluencesStoredPath() throws IOException {
        List<ProductImageStorageService.StoredImage> stored = service.store(new MultipartFile[]{
                file("../../escape.png", "image/png", PNG), file("..\\..\\escape2.png", "image/png", PNG)});

        assertThat(stored).hasSize(2);
        assertThat(stored).allSatisfy(image -> {
            assertThat(image.path().normalize().startsWith(root.toAbsolutePath().normalize())).isTrue();
            assertThat(image.path().getFileName().toString()).doesNotContain("escape", "..", "/", "\\");
        });
        assertThat(Files.exists(tempDir.resolve("escape.png"))).isFalse();
        assertThat(Files.exists(root.getParent().resolve("escape.png"))).isFalse();
    }

    @Test
    void rejectsDisallowedContentTypeExtensionMismatchAndForgedContent() throws IOException {
        assertBadRequest(file("a.svg", "image/svg+xml", "<svg/>".getBytes()));
        assertBadRequest(file("a.gif", "image/gif", PNG));
        assertBadRequest(file("a.png", "text/html", PNG));
        assertBadRequest(file("a.exe", "image/png", PNG));
        assertBadRequest(file("noextension", "image/png", PNG));
        assertBadRequest(file("a.jpg", "image/png", PNG));
        assertBadRequest(file("a.png", "image/png", "<html><script>alert(1)</script></html>".getBytes()));
        assertBadRequest(file("a.jpg", "image/jpeg", PNG));
        assertBadRequest(file("a.webp", "image/webp", new byte[]{'R', 'I', 'F', 'F'}));
        assertBadRequest(new MockMultipartFile("images", "a.png", null, PNG));
        assertThat(listNames(root)).isEmpty();
    }

    @Test
    void rejectsEmptyOversizedAndBadCountRequests() throws IOException {
        assertBadRequest(file("a.png", "image/png", new byte[0]));
        byte[] tooBig = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, tooBig, 0, PNG.length);
        assertBadRequest(file("big.png", "image/png", tooBig));

        assertThatThrownBy(() -> service.store(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.store(new MultipartFile[0])).isInstanceOf(BusinessException.class);
        MultipartFile[] six = new MultipartFile[6];
        for (int i = 0; i < six.length; i++) six[i] = file("a" + i + ".png", "image/png", PNG);
        assertThatThrownBy(() -> service.store(six))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.store(new MultipartFile[]{null})).isInstanceOf(BusinessException.class);
        assertThat(listNames(root)).isEmpty();
    }

    @Test
    void batchFailureRemovesFilesAlreadyWrittenByEarlierItems() throws IOException {
        assertThatThrownBy(() -> service.store(new MultipartFile[]{
                file("ok.png", "image/png", PNG), file("bad.png", "image/png", "not an image".getBytes())}))
                .isInstanceOf(BusinessException.class);

        assertThat(listNames(root)).isEmpty();
    }

    @Test
    void deleteQuietlyRemovesStoredFileAndToleratesMissingFile() throws IOException {
        ProductImageStorageService.StoredImage image = service.store(new MultipartFile[]{file("a.png", "image/png", PNG)}).get(0);

        service.deleteQuietly(image);
        service.deleteQuietly(image);

        assertThat(image.path()).doesNotExist();
    }

    @Test
    void partiallyWrittenFileIsRemovedWhenTransferFails() throws IOException {
        MockMultipartFile failing = new MockMultipartFile("images", "a.png", "image/png", PNG) {
            @Override
            public void transferTo(Path dest) throws IOException {
                Files.write(dest, new byte[]{1});
                throw new IOException("disk full");
            }
        };

        assertThatThrownBy(() -> service.store(new MultipartFile[]{failing}))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(listNames(root)).isEmpty();
    }

    private void assertBadRequest(MultipartFile file) {
        assertThatThrownBy(() -> service.store(new MultipartFile[]{file}))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("images", name, contentType, content);
    }

    private static List<String> listNames(Path directory) throws IOException {
        if (!Files.exists(directory)) return List.of();
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).toList();
        }
    }
}
