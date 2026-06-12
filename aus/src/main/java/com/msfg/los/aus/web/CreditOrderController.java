package com.msfg.los.aus.web;

import com.msfg.los.aus.service.CreditOrderService;
import com.msfg.los.aus.web.dto.CreditOrderRequest;
import com.msfg.los.aus.web.dto.CreditOrderResponse;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Credit ordering: port-backed order/reissue + newest-first order history. */
@RestController
@RequestMapping("/api/loans/{loanId}/credit")
public class CreditOrderController {

    private final CreditOrderService service;

    public CreditOrderController(CreditOrderService service) {
        this.service = service;
    }

    @PostMapping("/order")
    public ResponseEntity<ApiResponse<CreditOrderResponse>> order(@PathVariable UUID loanId,
                                                                  @RequestBody CreditOrderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.order(loanId, req)));
    }

    @GetMapping("/orders")
    public ApiResponse<List<CreditOrderResponse>> history(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.history(loanId));
    }
}
