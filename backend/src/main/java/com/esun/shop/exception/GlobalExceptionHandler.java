package com.esun.shop.exception;

import com.esun.shop.dto.ApiResponse;
import com.esun.shop.service.ConcurrentOrderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.fail(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        var fieldError = ex.getBindingResult().getFieldError();
        String msg = fieldError != null
                ? fieldError.getField() + "：" + fieldError.getDefaultMessage()
                : "參數驗證失敗";
        return ResponseEntity.badRequest().body(ApiResponse.fail(msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("請求格式錯誤或欄位值不合法"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKey(DuplicateKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail("資料已存在"));
    }

    @ExceptionHandler(ConcurrentOrderException.class)
    public ResponseEntity<ApiResponse<Void>> handleConcurrentOrder(ConcurrentOrderException ex) {
        log.warn("訂單因鎖競爭重試後仍失敗，requestId={}", ex.getRequestId());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("CONCURRENT_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupported(UnsupportedOperationException ex) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ApiResponse.fail(ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDb(DataAccessException ex) {
        log.error("資料庫操作失敗", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("DB_ERROR", "資料庫操作失敗"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex) {
        log.error("系統發生未預期錯誤", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail("系統發生未預期錯誤"));
    }
}
