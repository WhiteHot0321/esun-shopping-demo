package com.esun.shop.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.esun.shop.dto.ApiResponse;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unsupportedHttpMethodReturns405AndAllowHeader() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new MethodContractController())
                .setControllerAdvice(handler).build();

        mvc.perform(delete("/method-contract"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.message").value("不支援此 HTTP 方法"));
        mvc.perform(get("/method-contract")).andExpect(status().isOk());
    }

    @Test
    void unexpectedExceptionsRemain500WithoutLeakingDetails() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new MethodContractController())
                .setControllerAdvice(handler).build();

        mvc.perform(get("/method-contract/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("系統發生未預期錯誤"));
    }

    @RestController
    static class MethodContractController {
        @GetMapping("/method-contract")
        String getOnly() { return "ok"; }

        @GetMapping("/method-contract/failure")
        String fail() { throw new IllegalStateException("internal-only details"); }
    }

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
