package com.esun.shop.exception;

import com.esun.shop.dto.ApiResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldError() != null
                ? ex.getBindingResult().getFieldError().getField() + " 格式錯誤"
                : "參數驗證失敗";
        return ApiResponse.fail(msg);
    }

    @ExceptionHandler(DataAccessException.class)
    public ApiResponse<Void> handleDb(DataAccessException ex) {
        ex.printStackTrace();
        return ApiResponse.fail("資料庫操作失敗");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleOther(Exception ex) {
        return ApiResponse.fail("系統發生未預期錯誤");
    }
}
