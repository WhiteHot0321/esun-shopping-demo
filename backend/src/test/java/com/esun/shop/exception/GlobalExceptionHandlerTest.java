package com.esun.shop.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.esun.shop.dto.ApiResponse;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void stockProcedureSignalIsAnOutOfStockConflictNotAServerError() {
        var signal = new UncategorizedSQLException("CallableStatementCallback", "{call sp_decrease_stock(?, ?)}",
                new SQLException("out of stock", "45000", 1644));

        ResponseEntity<ApiResponse<Void>> response = handler.handleDb(signal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getMessage()).isEqualTo("商品庫存不足");
    }

    @Test
    void otherDatabaseFailuresStayServerErrors() {
        var otherSignal = new UncategorizedSQLException("x", "sql", new SQLException("other", "45000", 1645));
        var lockTimeout = new CannotAcquireLockException("Lock wait timeout exceeded", new SQLException("t", "40001", 1205));

        assertThat(handler.handleDb(otherSignal).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(handler.handleDb(lockTimeout).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(handler.handleDb(lockTimeout).getBody().getCode()).isEqualTo("DB_ERROR");
    }
}
