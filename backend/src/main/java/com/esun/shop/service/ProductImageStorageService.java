package com.esun.shop.service;

import com.esun.shop.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductImageStorageService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp");
    private final Path root;

    public ProductImageStorageService(@Value("${product.images.directory:uploads/products}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    public List<StoredImage> store(MultipartFile[] files) {
        if (files == null || files.length == 0 || files.length > 5) {
            throw new BusinessException("每次請上傳 1 至 5 張圖片", HttpStatus.BAD_REQUEST);
        }
        List<StoredImage> stored = new ArrayList<>();
        try {
            Files.createDirectories(root);
            for (MultipartFile file : files) stored.add(storeOne(file));
            return stored;
        } catch (RuntimeException | IOException ex) {
            stored.forEach(this::deleteQuietly);
            if (ex instanceof BusinessException businessException) throw businessException;
            throw new BusinessException("圖片儲存失敗", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public void deleteQuietly(StoredImage image) {
        try { Files.deleteIfExists(image.path()); } catch (IOException ignored) { }
    }

    private StoredImage storeOne(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new BusinessException("圖片不可為空", HttpStatus.BAD_REQUEST);
        if (file.getSize() > MAX_BYTES) throw new BusinessException("單張圖片不可超過 5MB", HttpStatus.BAD_REQUEST);
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String expectedExtension = ALLOWED_TYPES.get(contentType);
        if (expectedExtension == null) throw new BusinessException("僅支援 JPEG、PNG、WEBP 圖片", HttpStatus.BAD_REQUEST);
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        int dot = original.lastIndexOf('.');
        String suppliedExtension = dot < 0 ? "" : original.substring(dot);
        if (!ALLOWED_EXTENSIONS.contains(suppliedExtension)
                || (contentType.equals("image/jpeg") && !Set.of(".jpg", ".jpeg").contains(suppliedExtension))
                || (!contentType.equals("image/jpeg") && !expectedExtension.equals(suppliedExtension))) {
            throw new BusinessException("圖片副檔名與格式不符", HttpStatus.BAD_REQUEST);
        }
        if (!matchesSignature(file, contentType)) {
            throw new BusinessException("圖片內容與宣告格式不符", HttpStatus.BAD_REQUEST);
        }
        // 檔名一律由伺服器產生，client 提供的檔名只用來比對副檔名，不參與任何路徑組合。
        String generatedName = UUID.randomUUID() + expectedExtension;
        Path target = root.resolve(generatedName).normalize();
        if (!target.startsWith(root)) throw new BusinessException("不安全的圖片路徑", HttpStatus.BAD_REQUEST);
        try {
            file.transferTo(target);
        } catch (IOException | RuntimeException ex) {
            // 寫到一半失敗的檔案尚未加入 stored 清單，需在此自行清除，避免留下孤兒檔。
            try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            throw ex;
        }
        return new StoredImage("/uploads/products/" + generatedName, target);
    }

    private static boolean matchesSignature(MultipartFile file, String contentType) throws IOException {
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        }
        return switch (contentType) {
            case "image/jpeg" -> read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
                    && (head[2] & 0xFF) == 0xFF;
            case "image/png" -> read >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N'
                    && head[3] == 'G' && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A;
            case "image/webp" -> read >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
    }

    public record StoredImage(String url, Path path) { }
}
